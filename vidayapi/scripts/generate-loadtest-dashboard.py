#!/usr/bin/env python3
"""ЛР4 — генератор дашбордов Grafana «Нагрузочное тестирование vidayapi».

Дашборды отдельные от ЛР3 и используют PostgreSQL benchmark_pg для постоянной
истории запросов, ресурсов контейнеров и Actuator-снимков. Prometheus
prometheus_lr4 остаётся только для операционной диагностики.

Сценарии разведены по разным доскам, чтобы отчёт не смешивал разные профили
нагрузки:
  • uid=loadtest-lr4-auth   — WORKLOAD=auth (матрикс: login → … → unfollow);
  • uid=loadtest-lr4-public — WORKLOAD=public (только публичные чтения).

На каждой доске workload зафиксирован в SQL: в списке прогонов, деталях
сценария и Live-метриках видны только прогоны этого сценария.

Сверху только два переключателя:
  • «Тип прогона» (ramp/stress/soak/soak-errors/smoke) — фильтрует список
    прогонов и адаптивную таблицу деталей;
  • «Прогон» — конкретный run-id этого типа.

Что показывает каждая доска (соответствие заданию ЛР4):
  • LIVE из Prometheus: RPS, VU, p95/p99, отказы, CPU/RAM, JVM heap, HTTP 5xx;
  • сводку прогонов, KPI, пик отказов и окна выше порога 95%;
  • главный график «VU и RPS во времени» + задержки/отказы/долю отказов;
  • адаптивные детали: ramp → ступени lt_ramp_curve; stress/soak → фазы
    lt_phases с временем восстановления;
  • CDF и гистограмму во времени;
  • операции, ресурсы компонентов, JVM heap/GC/HikariCP/HTTP, выводы (findings).

Все графики строятся по реальным таймстемпам (`bucket_ts`/`ts`) и фильтруются
таймпикером Grafana: CDF и гистограмма раскладываются по 5-секундным окнам
(`lt_cdf_ts` / `lt_histogram_ts`), перцентили p50/p95/p99 идут long-форматом.

Запуск:
  python3 scripts/generate-loadtest-dashboard.py                # обе доски
  DASHBOARD_WORKLOAD=auth python3 scripts/generate-loadtest-dashboard.py
"""
import json
import os
import subprocess
import sys

PG = {"type": "grafana-postgresql-datasource", "uid": "benchmark_pg"}
PROM = {"type": "prometheus", "uid": "prometheus_lr4"}

WORKLOADS = ("auth", "public")
WORKLOAD = (os.environ.get("DASHBOARD_WORKLOAD") or "auth").strip().lower()
if WORKLOAD not in WORKLOADS:
    raise SystemExit(f"DASHBOARD_WORKLOAD must be one of {WORKLOADS}, got {WORKLOAD!r}")

DASHBOARD_UID = f"loadtest-lr4-{WORKLOAD}"
WORKLOAD_RU = {
    "auth": "auth (матрикс): login → feed → video → playlist → … → unfollow",
    "public": "public: feed → video_detail → live_streams (без логина и записи)",
}[WORKLOAD]
OTHER_WORKLOAD = "public" if WORKLOAD == "auth" else "auth"

# Подзапрос устойчив к пустой/устаревшей переменной $run: если выбранный run
# не относится к текущему типу прогона, берётся самый свежий run этого типа.
# Фильтр по workload гарантирует, что на доске auth/public выбирается прогон
# именно этого сценария, даже если в базе есть более свежие прогоны другого.
RUN = (
    "(SELECT COALESCE("
    "(SELECT run_id FROM lt_runs WHERE run_id = NULLIF('$run', '') "
    f"AND scenario = '$scenario' AND COALESCE(config->>'workload', 'auth') = '{WORKLOAD}'), "
    "(SELECT run_id FROM lt_runs WHERE scenario = '$scenario' "
    f"AND COALESCE(config->>'workload', 'auth') = '{WORKLOAD}' "
    "ORDER BY started_at DESC LIMIT 1)))"
)

# Никаких $phase/$operation/$percentile: все отчётные панели показывают
# весь прогон (все фазы) и агрегат по всем операциям. Это убирает
# «половину пустых панелей» и делает сценарии сравнимыми между собой.
TS_OPERATION = "operation = 'all'"
DIST_SCOPE = "'overall'"
PERCENTILE_COLUMNS = [
    ("p50", "p50_ms"), ("p75", "p75_ms"), ("p90", "p90_ms"),
    ("p95", "p95_ms"), ("p99", "p99_ms"), ("p997", "p997_ms"),
]
LATENCY_SERIES = [("p50", "p50_ms"), ("p95", "p95_ms"), ("p99", "p99_ms")]


def distribution_phase():
    """Худшая фаза прогона по p95 — для CDF/гистограммы.

    Смешивать baseline/overload/recovery в одном распределении нельзя:
    получается неоднородная смесь, где хвост перегруза «размазан» по всей
    выборке. Для ramp/soak фаза одна, для stress выбирается overload.
    """
    return f"""(SELECT s.phase
        FROM lt_scenario_summary s
        WHERE s.run_id = {RUN} AND s.scope = 'overall' AND s.operation = 'all'
        ORDER BY s.p95_ms DESC NULLS LAST, s.phase
        LIMIT 1)"""


DIST_PHASE = distribution_phase()

METHOD_TEXT = f"""## ЛР4: нагрузочное тестирование vidayapi — `WORKLOAD={WORKLOAD}`

**Эта доска содержит только прогоны `{WORKLOAD}`.** Сценарий: {WORKLOAD_RU}.
Прогоны другого сценария — на отдельной доске
[`loadtest-lr4-{OTHER_WORKLOAD}`](/d/loadtest-lr4-{OTHER_WORKLOAD}), чтобы auth и
public не смешивались на одной оси X.

Каждый прогон использует отдельные образы приложения и генератора, чистый том
PostgreSQL и детерминированный сид. Нагрузку создаёт собственный Kotlin-генератор:
**корутины + собственный диспетчер с фиксированным пулом потоков**. Параметры железа,
корутин и потоков показаны в структурированных таблицах выбранного прогона.

**Переключатели сверху — только два:** **«Тип прогона»** (ramp / stress / soak /
soak-errors / smoke) и **«Прогон»**. Тип прогона фильтрует список прогонов и
адаптивную таблицу деталей; все графики ниже общие, потому что VU/RPS, задержки,
отказы, ресурсы и JVM нужны любому сценарию. Панелей «для другого сценария»,
которые всегда пустые, на доске нет.

**Live — первым делом.** Секция `LIVE` читает Prometheus (ресурсы контейнеров,
Actuator и live-метрики Kotlin-генератора) и **не зависит от выбранного прогона**:
запустите тест при открытой Grafana — RPS, VU, p95/p99, отказы, CPU и RAM появятся
в течение 10 с. Когда прогон завершится и импортируется в PostgreSQL, он появится
в списке «Прогон» (обновление списка — при смене таймпикера или перезагрузке).

**Главный график:** «VU и RPS во времени» — две оси на одном полотне. Именно по нему
видно, как рост виртуальных пользователей перестаёт давать прирост запросов, а затем
начинаются отказы. «Доля отказов в 5-с окне» с порогом 95% показывает падение сервиса:
несколько подряд полных окон выше порога — это и есть критерий ранней остановки.

**VU = корутина.** Генератор физически не может поднять больше виртуальных
пользователей, чем `COROUTINES`; ramp-план автоматически обрезается до этого потолка,
поэтому «RAMP_MAX_VUS=300 при COROUTINES=100» даст плато на 100 VU, а не 300.
Готовый сценарий падения на 1 CPU: `make loadtest-fail` (auth, 100 корутин,
RAM 256 MiB, ramp 10→100 VU, остановка по 3 окнам ≥95% отказов).

**Почему public не падает на 100 корутинах:** публичные чтения на 1 CPU держат
~1000–1100 RPS при p95 ~100 мс даже при 100% CPU; замкнутый контур из 100 запросов
не создаёт очередь в 2 с, поэтому 5xx/timeout не появляются. Для демонстрации отказа
нужен auth-сценарий с логином и записью — он падает уже на ~40–50 VU.

**Детали сценария** — одна адаптивная таблица: для ramp это ступени
(lt_ramp_curve: VU → RPS/p95/p99/отказы/стабильность), для stress/soak — фазы
(lt_phases: baseline → overload → recovery, RPS, перцентили и время восстановления).
Поэтому таблица всегда заполнена и не зависит от того, какой сценарий выбран.

**Время.** Все ряды построены по реальным таймстемпам (`bucket_ts` / `ts`) и
фильтруются таймпикером. В таблице «Прогоны в базе» клик по `run_id` открывает этот
же дашборд с таймпикером ровно на интервале прогона — удобно для коротких
early-stopped прогонов.

**Отказ** = 5xx / timeout / обрыв. Инъекции 4xx и бизнес-4xx отказом не считаются.
Ранняя остановка: после 30 с три подряд полных 5-с окна, в каждом ≥20 запросов и
≥95% отказов (успех ≤5%). CPU нормализован к лимиту контейнера: **100% = все
доступные ядра**. Перцентили p50/p95/p99 показаны на логарифмической оси.

Ссылки: [ЛР3 — микробенчмарк](/d/benchmark-lr3) ·
[`loadtest-lr4-{OTHER_WORKLOAD}`](/d/loadtest-lr4-{OTHER_WORKLOAD}) ·
[отчёт ЛР4](https://github.com/Snowkey505/viday/blob/main/vidayapi/docs/lr4.md) ·
[гайд](https://github.com/Snowkey505/viday/blob/main/vidayapi/docs/lr4-grafana-guide.md)
"""

RUNS_SQL = f"""
WITH per_run AS (
  SELECT run_id,
         sum(requests) AS requests,
         sum(requests) / NULLIF(sum(duration_s), 0) AS rps,
         max(p50_ms) AS p50_ms,
         max(p95_ms) AS p95_ms,
         max(p99_ms) AS p99_ms,
         sum(errors_unexpected)::double precision
           / NULLIF(sum(requests), 0) * 100 AS fail_pct,
         sum(errors_injected)::double precision
           / NULLIF(sum(requests), 0) * 100 AS injected_pct,
         max(vus_max) AS vus_max
  FROM lt_scenario_summary
  WHERE scope = 'overall'
  GROUP BY run_id
)
SELECT r.run_id AS "прогон",
       r.profile AS "профиль",
       r.status AS "статус",
       COALESCE(r.config->>'workload', 'auth') AS "сценарий",
       COALESCE(r.config->>'api_cpus', '2') AS "CPU",
       COALESCE(r.config->>'api_ram_mb', '1536') AS "RAM MiB",
       COALESCE(r.config->>'coroutines', '-') AS "корутины",
       COALESCE(r.config->>'dispatcher_threads', '-') AS "потоки dispatcher",
       to_char(r.started_at, 'YYYY-MM-DD HH24:MI') AS "старт",
       round(r.duration_s)::int AS "длит., с",
       round(r.error_rate_target * 100)::int AS "цель ошибок, %",
       p.requests AS "запросы",
       round(p.rps::numeric, 1) AS "RPS",
       round(p.p50_ms::numeric, 1) AS "p50 (макс. фаза), мс",
       round(p.p95_ms::numeric, 1) AS "p95 (макс. фаза), мс",
       round(p.p99_ms::numeric, 1) AS "p99 (макс. фаза), мс",
       round(p.fail_pct::numeric, 2) AS "отказы, %",
       round(p.injected_pct::numeric, 2) AS "инъекции, %",
       round(p.vus_max::numeric, 0) AS "VU max",
       CASE WHEN COALESCE(r.config->>'early_stopped', 'false')::boolean
            THEN 'да' ELSE 'нет' END AS "досрочно",
       COALESCE(r.config->>'termination_reason', '') AS "причина остановки",
       round(EXTRACT(EPOCH FROM r.started_at) * 1000)::bigint AS from_ms,
       round(EXTRACT(EPOCH FROM r.finished_at) * 1000)::bigint AS to_ms
FROM lt_runs r
LEFT JOIN per_run p ON p.run_id = r.run_id
WHERE COALESCE(r.config->>'workload', 'auth') = '{WORKLOAD}'
  AND r.scenario = '$scenario'
ORDER BY r.started_at DESC
"""

KPI_SQL = f"""
SELECT sum(requests)::bigint AS "запросы",
       round((sum(requests) / NULLIF(sum(duration_s), 0))::numeric, 1) AS "RPS",
       round(max(p95_ms)::numeric, 1) AS "p95, мс",
       round(max(p99_ms)::numeric, 1) AS "p99, мс",
       round((sum(errors_unexpected)::double precision
              / NULLIF(sum(requests), 0) * 100)::numeric, 2) AS "отказы, %",
       round((sum(errors_injected)::double precision
              / NULLIF(sum(requests), 0) * 100)::numeric, 2) AS "инъекции, %",
       round(max(vus_max)::numeric, 0) AS "VU max"
FROM lt_scenario_summary
WHERE run_id = {RUN} AND scope = 'overall' AND operation = 'all'
"""


def latency_long_sql():
    """Перцентили p50/p95/p99 в long-формате (time, metric, value).

    Wide-формат с NULL-колонками ломал панель: transformation filterFieldsByName
    мог убрать time, и Grafana показывала «Data is missing a time field».
    Long-формат PostgreSQL-датасорс сам разворачивает в серии по metric.
    """
    parts = []
    for label, column in LATENCY_SERIES:
        parts.append(f"""
  SELECT bucket_ts, '{label}' AS metric, {column} AS value
  FROM base""")
    return f"""
WITH base AS (
  SELECT bucket_ts, {', '.join(column for _label, column in LATENCY_SERIES)}
  FROM lt_timeseries
  WHERE run_id = {RUN} AND $__timeFilter(bucket_ts) AND {TS_OPERATION}
),
long AS ({' UNION ALL '.join(parts)}
)
SELECT bucket_ts AS "time", metric, round(value::numeric, 2) AS "value"
FROM long
ORDER BY 1
"""


LATENCY_SQL = latency_long_sql()

RPS_SQL = f"""
WITH base AS (
  SELECT bucket_ts, rps, vus
  FROM lt_timeseries
  WHERE run_id = {RUN} AND $__timeFilter(bucket_ts) AND {TS_OPERATION}
)
SELECT b.bucket_ts AS "time",
       round(sum(b.rps)::numeric, 2) AS "RPS",
       round(avg(b.vus)::numeric, 1) AS "VU"
FROM base b
GROUP BY 1
ORDER BY 1
"""

ERRORS_SQL = f"""
WITH base AS (
  SELECT bucket_ts, errors_unexpected, errors_injected, errors_business
  FROM lt_timeseries
  WHERE run_id = {RUN} AND $__timeFilter(bucket_ts) AND {TS_OPERATION}
)
SELECT b.bucket_ts AS "time",
       sum(b.errors_unexpected)::double precision AS "отказы (5xx/timeout)",
       sum(b.errors_injected)::double precision AS "инъекции 4xx",
       sum(b.errors_business)::double precision AS "прочие 4xx"
FROM base b
GROUP BY 1
ORDER BY 1
"""

COMPLETION_SQL = f"""
WITH base AS (
  SELECT bucket_ts, requests, errors_unexpected
  FROM lt_timeseries
  WHERE run_id = {RUN} AND $__timeFilter(bucket_ts) AND {TS_OPERATION}
)
SELECT b.bucket_ts AS "time",
       round((sum(b.requests - b.errors_unexpected) / 5.0)::numeric, 3)
         AS "успешные операции/с",
       round((sum(b.errors_unexpected) / 5.0)::numeric, 3)
         AS "отказы/с"
FROM base b
GROUP BY 1
ORDER BY 1
"""

CUMULATIVE_COMPLETION_SQL = f"""
WITH base AS (
  SELECT bucket_ts, requests, errors_unexpected
  FROM lt_timeseries
  WHERE run_id = {RUN} AND $__timeFilter(bucket_ts) AND {TS_OPERATION}
)
SELECT b.bucket_ts AS "time",
       sum(b.requests - b.errors_unexpected) OVER (ORDER BY b.bucket_ts)::bigint
         AS "успешные операции, накоплено",
       sum(b.errors_unexpected) OVER (ORDER BY b.bucket_ts)::bigint
         AS "отказы, накоплено"
FROM base b
ORDER BY b.bucket_ts
"""

CROSS_SQL = f"""
WITH base AS (
  SELECT bucket_ts, requests, errors_unexpected
  FROM lt_timeseries
  WHERE run_id = {RUN} AND $__timeFilter(bucket_ts) AND {TS_OPERATION}
),
crossing AS (
  SELECT b.bucket_ts
  FROM base b
  WHERE b.requests > 0 AND b.errors_unexpected > b.requests - b.errors_unexpected
  ORDER BY b.bucket_ts
  LIMIT 1
)
-- Возвращаем именно таймстемп первого окна, а не секунды от старта.
SELECT COALESCE(
         to_char((SELECT bucket_ts FROM crossing), 'YYYY-MM-DD HH24:MI:SS'),
         'не достигнуто'
       ) AS value
"""

def resource_ts_sql(value_expr):
    """Ресурсы по компонентам на реальной шкале времени.

    Диапазон времени Grafana (таймпикер сверху) фильтрует точки через
    $__timeFilter: можно рассматривать как один прогон, так и несколько
    соседних на общей шкале.
    """
    return f"""
WITH base AS (
  SELECT ts, component, ({value_expr}) AS value
  FROM lt_resource_samples
  WHERE run_id = {RUN} AND $__timeFilter(ts)
)
SELECT b.ts AS "time",
       round(max(b.value) FILTER (WHERE b.component = 'api')::numeric, 2) AS "api",
       round(max(b.value) FILTER (WHERE b.component = 'postgres')::numeric, 2) AS "postgres",
       round(max(b.value) FILTER (WHERE b.component = 'redis')::numeric, 2) AS "redis",
       round(max(b.value) FILTER (WHERE b.component = 'minio')::numeric, 2) AS "minio",
       round(max(b.value) FILTER (WHERE b.component IN ('k6', 'generator'))::numeric, 2) AS "generator"
FROM base b
GROUP BY 1
ORDER BY 1
"""


RESOURCE_CPU_SQL = resource_ts_sql("cpu_pct")
RESOURCE_RAM_SQL = resource_ts_sql("ram_bytes / 1048576.0")
RESOURCE_DISK_READ_SQL = resource_ts_sql("disk_read_bps / 1048576.0")
RESOURCE_DISK_WRITE_SQL = resource_ts_sql("disk_write_bps / 1048576.0")
RESOURCE_NET_RX_SQL = resource_ts_sql("net_rx_bps / 1048576.0")
RESOURCE_NET_TX_SQL = resource_ts_sql("net_tx_bps / 1048576.0")

# Временные версии распределений: X — таймстемпы, поэтому таймпикер
# управляет тем, какой интервал прогона показывать.
CDF_THRESHOLDS_MS = [10, 25, 50, 100, 200, 500, 1000, 2000, 5000, 10000]
CDF_TS_SQL = f"""
WITH base AS (
  SELECT bucket_ts, latency_ms, max(fraction) AS fraction
  FROM lt_cdf_ts
  WHERE run_id = {RUN} AND $__timeFilter(bucket_ts) AND {TS_OPERATION}
  GROUP BY bucket_ts, latency_ms
)
SELECT bucket_ts AS "time",
       {', '.join(
           f'round(max(fraction) FILTER (WHERE latency_ms = {threshold})::numeric, 3) AS "≤{threshold} мс"'
           for threshold in CDF_THRESHOLDS_MS
       )}
FROM base
GROUP BY 1
ORDER BY 1
"""

HIST_TS_SQL = f"""
SELECT bucket_ts AS "time",
       round(((from_ms + to_ms) / 2.0)::numeric, 1) AS "latency_ms",
       sum(count)::bigint AS "requests"
FROM lt_histogram_ts
WHERE run_id = {RUN} AND $__timeFilter(bucket_ts) AND {TS_OPERATION}
GROUP BY bucket_ts, from_ms, to_ms
ORDER BY 1, 2
"""

OPERATIONS_SQL = f"""
SELECT operation AS "операция",
       sum(requests) AS "запросов",
       round((sum(requests) / NULLIF(sum(duration_s), 0))::numeric, 1) AS "RPS",
       round(max(p50_ms)::numeric, 1) AS "p50",
       round(max(p95_ms)::numeric, 1) AS "p95",
       round(max(p99_ms)::numeric, 1) AS "p99",
       sum(errors_unexpected) AS "отказы",
       sum(errors_injected) AS "инъекции"
FROM lt_scenario_summary
WHERE run_id = {RUN} AND scope = 'operation'
GROUP BY operation
ORDER BY sum(requests) DESC
"""

TOP_OPS_SQL = f"""
SELECT operation AS "операция",
       round(max(p95_ms)::numeric, 1) AS "p95, мс"
FROM lt_scenario_summary
WHERE run_id = {RUN} AND scope = 'operation'
GROUP BY operation
ORDER BY max(requests) DESC
LIMIT 15
"""

RESOURCE_SUMMARY_SQL = f"""
SELECT component AS "компонент",
       samples AS "сэмплов",
       round(cpu_min::numeric, 2) || ' / ' || round(cpu_max::numeric, 2)
         || ' / ' || round(cpu_median::numeric, 2) AS "CPU %, min/max/median",
       round((ram_min / 1048576.0)::numeric, 1) || ' / '
         || round((ram_max / 1048576.0)::numeric, 1) || ' / '
         || round((ram_median / 1048576.0)::numeric, 1) AS "RAM MiB, min/max/median",
       round((disk_read_median / 1048576.0)::numeric, 2) AS "диск чтение MiB/s (med)",
       round((disk_write_median / 1048576.0)::numeric, 2) AS "диск запись MiB/s (med)",
       round((net_rx_median / 1048576.0)::numeric, 2) AS "сеть rx MiB/s (med)",
       round((net_tx_median / 1048576.0)::numeric, 2) AS "сеть tx MiB/s (med)",
       pids_max AS "pids max"
FROM lt_resources
WHERE run_id = {RUN} AND scenario = 'all'
ORDER BY component
"""

RESOURCE_PHASES_SQL = f"""
SELECT phase AS "фаза",
       component AS "компонент",
       round(cpu_median::numeric, 2) AS "CPU med, %",
       round(cpu_max::numeric, 2) AS "CPU max, %",
       round((ram_median / 1048576.0)::numeric, 1) AS "RAM med, MiB",
       round((ram_max / 1048576.0)::numeric, 1) AS "RAM max, MiB",
       round((disk_write_median / 1048576.0)::numeric, 2) AS "disk write med, MiB/s",
       round((disk_read_median / 1048576.0)::numeric, 2) AS "disk read med, MiB/s"
FROM lt_resources
WHERE run_id = {RUN} AND phase <> 'all'
ORDER BY phase, component
"""

HARDWARE_SQL = f"""
SELECT parameter AS "параметр", value AS "значение"
FROM lt_runs r
CROSS JOIN LATERAL (VALUES
  ('Процессор', COALESCE(r.environment->>'cpu_model', 'не указан')),
  ('CPU limit API, ядер', COALESCE(r.config->>'api_cpus', 'не указан')),
  ('CPU cpuset API', COALESCE(r.environment->'cpusets'->>'api', 'не указан')),
  ('RAM limit API, MiB', COALESCE(r.config->>'api_ram_mb', 'не указан')),
  ('JVM heap Xmx, MiB', COALESCE(r.config->>'api_heap_mb', 'не указан')),
  ('JVM heap Xms, MiB', COALESCE(r.config->>'api_xms_mb', 'не указан')),
  ('RAM хоста доступно на старте, MiB', COALESCE(r.environment->>'host_mem_available_mb_at_start', 'не указано'))
) AS v(parameter, value)
WHERE r.run_id = {RUN}
"""

LOAD_CONFIG_SQL = f"""
SELECT parameter AS "параметр", value AS "значение"
FROM lt_runs r
CROSS JOIN LATERAL (VALUES
  ('Тип сценария', COALESCE(r.config->>'workload', 'auth')),
  ('Временной профиль', r.scenario),
  ('Генератор', COALESCE(r.config->>'generator', 'k6')),
  ('Корутины', COALESCE(r.config->>'coroutines', 'не применимо')),
  ('Потоки dispatcher', COALESCE(r.config->>'dispatcher_threads', 'не применимо')),
  ('Ramp: макс. VU в конфиге', COALESCE(r.config->>'ramp_max_vus', '—')),
  ('Потолок VU (COROUTINES)', COALESCE(r.config->>'coroutines', '—')),
  ('Операции на сессию', COALESCE(r.config->>'operations_per_session', '10')),
  ('Timeout запроса', COALESCE(r.config->>'request_timeout', 'не указан')),
  ('Критерий критического отказа', COALESCE(
     (r.config->>'failure_windows') || ' окна × ' || (r.config->>'failure_window') ||
     ', отказы ≥ ' || round(((r.config->>'failure_threshold')::numeric * 100), 0)::text || '%, запросов ≥ ' ||
     (r.config->>'failure_min_requests'), 'старый прогон: см. config'))
) AS v(parameter, value)
WHERE r.run_id = {RUN}
"""

CONFIG_SQL = f"""
SELECT key AS "параметр", value AS "значение"
FROM lt_runs r, jsonb_each_text(r.config)
WHERE r.run_id = {RUN}
ORDER BY key
"""

ENV_SQL = f"""
SELECT key AS "параметр", value AS "значение"
FROM lt_runs r, jsonb_each_text(r.environment)
WHERE r.run_id = {RUN}
ORDER BY key
"""

FINDINGS_SQL = f"""
SELECT kind AS "тип", key AS "показатель", value AS "значение", detail AS "детали"
FROM lt_findings
WHERE run_id = {RUN}
UNION ALL
SELECT 'info', 'результат', 'без специальных выводов',
       'Короткий smoke или стабильный прогон: пороги ramp/recovery/early-stop не активированы'
WHERE NOT EXISTS (SELECT 1 FROM lt_findings WHERE run_id = {RUN})
ORDER BY 1, 2
"""

# Детали, которые зависят от типа прогона, но живут в одной адаптивной таблице:
# ramp → ступени из lt_ramp_curve; stress/soak → фазы из lt_phases. Пустых
# панелей «для другого сценария» на доске нет.
ADAPTIVE_DETAILS_SQL = f"""
WITH meta AS (
  SELECT scenario FROM lt_runs WHERE run_id = {RUN}
),
ramp_rows AS (
  SELECT target_vus::double precision AS ord,
         'ступень ' || target_vus || ' VU' AS label,
         target_vus::double precision AS vus,
         requests,
         rps,
         p95_ms,
         p99_ms,
         unexpected_error_rate_pct AS fail_pct,
         NULL::text AS recovery_text,
         stable
  FROM lt_ramp_curve
  WHERE run_id = {RUN}
    AND (SELECT scenario FROM meta) = 'ramp'
),
phase_rows AS (
  SELECT EXTRACT(EPOCH FROM p.started_at) AS ord,
         p.phase AS label,
         (SELECT max(t.vus) FROM lt_timeseries t
           WHERE t.run_id = p.run_id AND t.phase = p.phase AND t.operation = 'all') AS vus,
         p.requests,
         p.rps,
         p.p95_ms,
         p.p99_ms,
         p.unexpected_error_rate_pct AS fail_pct,
         COALESCE(round(p.recovery_s::numeric, 0)::text, '—') AS recovery_text,
         (p.unexpected_error_rate_pct < 1 AND p.p95_ms < 500) AS stable
  FROM lt_phases p
  WHERE p.run_id = {RUN}
    AND (SELECT scenario FROM meta) <> 'ramp'
),
rows AS (
  SELECT * FROM ramp_rows
  UNION ALL
  SELECT * FROM phase_rows
)
SELECT label AS "ступень / фаза",
       round(vus::numeric, 0) AS "VU",
       requests AS "запросов",
       round(rps::numeric, 1) AS "RPS",
       round(p95_ms::numeric, 1) AS "p95, мс",
       round(p99_ms::numeric, 1) AS "p99, мс",
       round(fail_pct::numeric, 2) AS "отказы, %",
       COALESCE(recovery_text, '—') AS "восстановление, с",
       CASE WHEN stable THEN 'да' ELSE 'нет' END AS "стабильно"
FROM rows
ORDER BY ord
"""

# Доля отказов в каждом 5-с окне — главный индикатор «сервис упал».
FAILURE_RATE_SQL = f"""
SELECT bucket_ts AS "time",
       round((sum(errors_unexpected)::double precision
              / NULLIF(sum(requests), 0) * 100)::numeric, 2) AS "отказы, %"
FROM lt_timeseries
WHERE run_id = {RUN} AND $__timeFilter(bucket_ts) AND {TS_OPERATION}
GROUP BY 1
ORDER BY 1
"""

PEAK_FAILURE_SQL = f"""
SELECT round((max(errors_unexpected::double precision
                  / NULLIF(requests, 0) * 100))::numeric, 2)
FROM lt_timeseries
WHERE run_id = {RUN} AND {TS_OPERATION} AND requests > 0
"""

FAILURE_WINDOWS_SQL = f"""
SELECT count(*)::int
FROM lt_timeseries
WHERE run_id = {RUN} AND {TS_OPERATION} AND requests >= 20
  AND errors_unexpected::double precision >= 0.95 * requests
"""

def target(raw_sql, ref="A", fmt="table", datasource=None):
    return {
        "refId": ref,
        "datasource": datasource or PG,
        "editorMode": "code",
        "format": fmt,
        "rawQuery": True,
        "rawSql": raw_sql.strip(),
        "maxDataPoints": 4000,
    }


def prom(expr, legend_format="{{component}}", ref="A"):
    return {
        "refId": ref,
        "datasource": PROM,
        "editorMode": "code",
        "expr": expr,
        "legendFormat": legend_format,
        "range": True,
        "instant": False,
        "format": "time_series",
    }


def prom_instant(expr, ref="A"):
    """Мгновенный запрос Prometheus для таблицы/стата (метки как колонки)."""
    return {
        "refId": ref,
        "datasource": PROM,
        "editorMode": "code",
        "expr": expr,
        "range": False,
        "instant": True,
        "format": "table",
    }


def field_config(defaults=None, overrides=None):
    base = {
        "color": {"mode": "palette-classic"},
        "custom": {},
        "mappings": [],
        "thresholds": {"mode": "absolute", "steps": [{"color": "green", "value": None}]},
    }
    if defaults:
        defaults = dict(defaults)
        custom = defaults.pop("custom", None)
        base.update(defaults)
        if custom:
            base["custom"] = {**base.get("custom", {}), **custom}
    return {"defaults": base, "overrides": overrides or []}


def legend():
    return {"showLegend": True, "displayMode": "list", "placement": "bottom", "calcs": []}


def tooltip():
    return {"mode": "multi", "sort": "none"}


def line_fc(unit="ms", axis="", decimals=2, overrides=None, log=False):
    return field_config(
        {
            "unit": unit,
            "decimals": decimals,
            "custom": {
                "drawStyle": "line",
                "lineInterpolation": "linear",
                "lineWidth": 1,
                "fillOpacity": 12,
                "gradientMode": "none",
                "spanNulls": True,
                "showPoints": "never",
                "pointSize": 3,
                "stacking": {"mode": "none", "group": "A"},
                "axisPlacement": "auto",
                "axisLabel": axis,
                "axisColorMode": "text",
                "scaleDistribution": {"type": "log", "log": 10} if log else {"type": "linear"},
                "hideFrom": {"legend": False, "tooltip": False, "viz": False},
            },
        },
        overrides,
    )


def table_fc(overrides=None):
    return field_config(
        {
            "custom": {
                "align": "auto",
                "cellOptions": {"type": "auto"},
                "inspect": False,
            },
        },
        overrides,
    )


def panel(pid, ptype, title, grid, targets, fc=None, options=None, extra=None):
    body = {
        "id": pid,
        "type": ptype,
        "title": title,
        "gridPos": grid,
        "datasource": PG,
        "targets": targets if isinstance(targets, list) else [targets],
        "fieldConfig": fc or {"defaults": {}, "overrides": []},
        "options": options or {},
    }
    if extra:
        body.update(extra)
    return body


def row(pid, title, y):
    return {
        "id": pid,
        "type": "row",
        "title": title,
        "gridPos": {"h": 1, "w": 24, "x": 0, "y": y},
        "collapsed": False,
        "panels": [],
    }


def text_panel(pid, title, markdown, grid):
    return panel(
        pid, "text", title, grid, [],
        {"defaults": {}, "overrides": []},
        {"mode": "markdown", "code": {"language": "plaintext", "showLineNumbers": False, "showMiniMap": False}, "content": markdown},
    )


def stat(pid, title, sql, grid, unit="none", decimals=1, color_mode="value", mappings=None):
    return panel(
        pid, "stat", title, grid, target(sql),
        field_config({"unit": unit, "decimals": decimals, "mappings": mappings or []}),
        {
            "reduceOptions": {"values": False, "calcs": ["lastNotNull"], "fields": ""},
            "orientation": "auto",
            "textMode": "auto",
            "colorMode": color_mode,
            "graphMode": "area",
            "justifyMode": "auto",
        },
    )


def table(pid, title, sql, grid, h=8, w=24, overrides=None):
    return panel(
        pid, "table", title, {"h": h, "w": w, "x": 0, "y": grid},
        target(sql),
        table_fc(overrides),
        {
            "showHeader": True,
            "cellHeight": "sm",
            "footer": {"show": False, "reducer": ["sum"], "countRows": False, "fields": ""},
        },
    )


def prom_table(pid, title, expr, grid, h=7, w=8):
    """Таблица из мгновенного запроса Prometheus: метки показываются колонками."""
    body = panel(
        pid, "table", title, {"h": h, "w": w, "x": 0, "y": grid},
        [prom_instant(expr)],
        table_fc(),
        {
            "showHeader": True,
            "cellHeight": "sm",
            "footer": {"show": False, "reducer": ["sum"], "countRows": False, "fields": ""},
        },
        {"datasource": PROM, "maxDataPoints": 1000},
    )
    body["transformations"] = [{
        "id": "organize",
        "options": {
            "excludeByName": {
                "Time": True, "Value": True, "__name__": True,
                "job": True, "instance": True, "source": True,
            },
            "indexByName": {},
            "renameByName": {
                "run_id": "прогон",
                "workload": "workload",
                "profile": "профиль",
                "api_cpus": "CPU",
                "api_ram_mb": "RAM MiB",
                "coroutines": "корутины",
                "dispatcher_threads": "потоки",
            },
        },
    }]
    return body


def runs_overrides():
    """Ссылка из таблицы прогонов на этот же дашборд с зумом на интервал.

    Без зума 30-секундный прогон на широкой оси выглядит как один пиксель —
    отсюда «нет графиков». Клик по run_id выставляет from/to таймпикера.
    """
    return [
        {"matcher": {"id": "byName", "options": "from_ms"},
         "properties": [{"id": "custom.hidden", "value": True}]},
        {"matcher": {"id": "byName", "options": "to_ms"},
         "properties": [{"id": "custom.hidden", "value": True}]},
        {"matcher": {"id": "byName", "options": "прогон"},
         "properties": [{"id": "links", "value": [{
             "title": "Открыть этот прогон (зум на его интервал)",
             "url": (f'/d/{DASHBOARD_UID}?from=${{__data.fields["from_ms"]}}'
                     '&to=${__data.fields["to_ms"]}'
                     '&var-scenario=${scenario}&var-run=${__value.raw}'),
             "targetBlank": False,
         }]}]},
    ]


def x_override(field, unit, display, decimals=0):
    """Оформление числовой оси X у trend-панели.

    Grafana применяет fieldConfig.defaults (единицы Y) и к x-полю, поэтому без
    override ось «секунды от старта» подписывалась как «%» или «мс».
    """
    return {
        "matcher": {"id": "byName", "options": field},
        "properties": [
            {"id": "unit", "value": unit},
            {"id": "decimals", "value": decimals},
            {"id": "displayName", "value": display},
            {"id": "custom.axisLabel", "value": display},
        ],
    }


def ts(pid, title, sql, grid, unit="ms", axis="", decimals=2, legend_opts=None, log=False):
    """SQL-ряд на реальной шкале времени: фильтруется таймпикером Grafana."""
    return panel(
        pid, "timeseries", title, grid, target(sql, fmt="time_series"),
        line_fc(unit, axis, decimals, log=log),
        {"legend": legend_opts or legend(), "tooltip": tooltip()},
    )


def long_ts(pid, title, sql, grid, unit="ms", axis="", decimals=2, log=False, legend_opts=None):
    """Time series из long-формата (time, metric, value).

    Каждая строка metric становится отдельной линией; это позволяет вывести
    все выбранные прогоны и/или перцентили без знания их числа на этапе
    генерации дашборда.
    """
    # PostgreSQL datasource сам разворачивает long-формат (time, metric, value)
    # в wide time series: колонка metric становится именем серии. Отдельная
    # transformation не нужна и только ломает панель при неверных опциях.
    return panel(
        pid, "timeseries", title, grid, target(sql, fmt="time_series"),
        line_fc(unit, axis, decimals, log=log),
        {"legend": legend_opts or legend(), "tooltip": tooltip()},
    )


def prom_ts(pid, title, expr, grid, unit="percent", axis="", decimals=2, legend_format="{{component}}"):
    return prom_ts_multi(pid, title, [(expr, legend_format)], grid, unit, axis, decimals)


def prom_ts_multi(pid, title, exprs, grid, unit="short", axis="", decimals=0):
    """Prometheus-панель с несколькими запросами: exprs = [(expr, legend), ...]."""
    return panel(
        pid, "timeseries", title, grid,
        [prom(e, l, ref=f"A{i}") for i, (e, l) in enumerate(exprs)],
        line_fc(unit, axis, decimals),
        {"legend": legend(), "tooltip": tooltip()},
        # Панель должна явно использовать Prometheus: иначе Grafana берёт
        # datasource панели (PostgreSQL) и не отправляет expr в Prometheus.
        # Высокое разрешение важно для коротких прогонов: при диапазоне 7 дней
        # Grafana иначе выбирает шаг ~10–30 мин и может не попасть в 12–20-минутный
        # прогон, показывая «No data» для Prometheus-панелей.
        {"datasource": PROM, "maxDataPoints": 10000},
    )


def app_ts_sql(columns):
    selected = ",\n       ".join(
        f'round(max({column})::numeric, 3) AS "{label}"' for column, label in columns
    )
    return f"""
SELECT b.ts AS "time",
       {selected}
FROM lt_app_samples b
WHERE b.run_id = {RUN} AND $__timeFilter(b.ts)
GROUP BY b.ts
ORDER BY b.ts
"""


THREADS_SQL = app_ts_sql([
    ("threads_live", "JVM всего"),
    ("threads_daemon", "JVM daemon"),
    ("threads_runnable", "JVM runnable"),
    ("threads_waiting", "JVM waiting"),
    ("requests_in_flight", "запросов в полёте"),
    ("executor_active", "executor active"),
    ("executor_pool", "executor pool size"),
])
HEAP_SQL = app_ts_sql([("heap_bytes / 1048576.0", "heap used, MiB")])
GC_SQL = app_ts_sql([("gc_pause_rate", "GC pause, с/с")])
HIKARI_SQL = app_ts_sql([("hikari_active", "active"), ("hikari_idle", "idle")])
APP_HTTP_SQL = app_ts_sql([("http_rps", "HTTP RPS"), ("http_5xx_rps", "HTTP 5xx/с")])
HIST_JVM_SQL = app_ts_sql([("heap_bytes / 1048576.0", "heap, MiB"),
                           ("tomcat_active", "активные сессии")])
TOMCAT_ACTIVE_SQL = app_ts_sql([("tomcat_active", "active sessions")])
TOMCAT_REJECTED_SQL = app_ts_sql([("tomcat_rejected_rate", "rejected sessions/с")])


def resource_ts(pid, title, sql, grid, unit="percent", axis="", decimals=2):
    return panel(
        pid, "timeseries", title, grid, target(sql, fmt="time_series"),
        line_fc(unit, axis, decimals),
        {"legend": legend(), "tooltip": tooltip()},
    )


def query_var(name, label, sql, include_all=False, description="", multi=False,
              all_value="all", refresh=1):
    var = {
        "type": "query",
        "name": name,
        "label": label,
        "description": description,
        "datasource": PG,
        "query": sql.strip(),
        "definition": sql.strip(),
        # 2 = обновлять при изменении таймпикера. Автообновление дашборда
        # сдвигает now, поэтому список прогонов подхватывает завершённый run
        # без перезагрузки страницы.
        "refresh": refresh,
        # 0 = сохранять порядок из SQL (для run — новые сверху), а не
        # сортировать options по алфавиту.
        "sort": 0,
        "multi": multi,
        "includeAll": include_all,
        "current": {},
        "options": [],
        "skipUrlSync": False,
        "hide": 0,
    }
    if include_all:
        var["allValue"] = all_value
        var["current"] = {"selected": True, "text": "All", "value": all_value}
    return var


def scenario_var():
    """Тип прогона — только те сценарии, которые реально есть в базе.

    Query-переменная (а не custom) не показывает «пустые» типы прогонов:
    например, у public нет stress/soak, и выбирать их незачем.
    """
    sql = f"""
SELECT r.scenario AS "__value",
       CASE r.scenario
         WHEN 'ramp' THEN 'Ramp (ступени)'
         WHEN 'stress' THEN 'Stress (baseline → overload → recovery)'
         WHEN 'soak' THEN 'Soak 10%'
         WHEN 'soak-errors' THEN 'Soak + ошибки'
         WHEN 'smoke' THEN 'Smoke'
         ELSE r.scenario
       END AS "__text"
FROM lt_runs r
WHERE COALESCE(r.config->>'workload', 'auth') = '{WORKLOAD}'
GROUP BY r.scenario
ORDER BY CASE r.scenario
           WHEN 'ramp' THEN 1 WHEN 'stress' THEN 2 WHEN 'soak' THEN 3
           WHEN 'soak-errors' THEN 4 ELSE 5
         END
"""
    return query_var(
        "scenario", "Тип прогона", sql,
        description="Фильтрует список прогонов и адаптивную таблицу деталей. "
                    "Графики общие для всех сценариев: VU/RPS, задержки, отказы, ресурсы.",
        refresh=2,
    )


def build_panels():
    panels = []
    pid = 1
    y = 0

    wl = WORKLOAD
    # «Активный прогон» = генератор отдаёт viday_loadtest_info прямо сейчас.
    # Без этого фильтра Prometheus показывает все прогоны за 24 ч и live-панель
    # превращается в кашу из старых run_id (warmup, soak, stress...).
    # Warmup исключаем: он живёт ещё минуту после своего завершения.
    active = (
        f'last_over_time(viday_loadtest_info{{workload="{wl}",run_id!~".*-warmup"}}[1m])'
    )
    live_rps = (
        f'sum by (run_id) (rate(viday_loadtest_requests_total{{workload="{wl}"}}[1m]))'
        f' and on (run_id) {active}'
    )
    live_vus = (
        f'max by (run_id) (viday_loadtest_vus{{workload="{wl}"}})'
        f' and on (run_id) {active}'
    )
    live_p95 = (
        '(histogram_quantile(0.95, sum by (le, run_id) '
        f'(rate(viday_loadtest_request_duration_seconds_bucket{{workload="{wl}"}}[1m])))'
        f' and on (run_id) {active}) * 1000'
    )
    live_p99 = (
        '(histogram_quantile(0.99, sum by (le, run_id) '
        f'(rate(viday_loadtest_request_duration_seconds_bucket{{workload="{wl}"}}[1m])))'
        f' and on (run_id) {active}) * 1000'
    )
    live_errors = (
        f'sum by (run_id, kind) (rate(viday_loadtest_errors_total{{workload="{wl}"}}[1m]))'
        f' and on (run_id) {active}'
    )
    live_cpu = (
        f'viday_container_cpu_percent{{component="api",workload="{wl}"}}'
        f' and on (run_id) {active}'
    )
    live_ram = (
        f'(viday_container_memory_working_set_bytes{{component="api",workload="{wl}"}}'
        f' and on (run_id) {active})'
        ' / 1024 / 1024'
    )
    # Actuator-метрики не несут run_id. Показываем их только пока живёт
    # viday_loadtest_info, иначе после прогона панель 24 часа тянет старую историю.
    active_any = (
        'count(last_over_time('
        f'viday_loadtest_info{{workload="{wl}",run_id!~".*-warmup"}}[30s])) > 0'
    )
    live_heap = (
        'sum(jvm_memory_used_bytes{job="viday-lr4-app",area="heap"}) / 1024 / 1024'
        f' and on() ({active_any})'
    )
    live_http = (
        'sum(rate(http_server_requests_seconds_count'
        '{job="viday-lr4-app",uri!~".*actuator.*"}[1m]))'
        f' and on() ({active_any})'
    )
    live_5xx = (
        'sum(rate(http_server_requests_seconds_count'
        '{job="viday-lr4-app",uri!~".*actuator.*",status=~"5.."}[1m]))'
        f' and on() ({active_any})'
    )

    # ------------------------------------------------------------------
    # Как читать
    # ------------------------------------------------------------------
    panels.append(row(pid, "ЛР4 — нагрузочное тестирование: методика и переключатели", y))
    pid += 1
    y += 1
    panels.append(text_panel(pid, "Методика", METHOD_TEXT, {"h": 7, "w": 24, "x": 0, "y": y}))
    pid += 1
    y += 7

    # ------------------------------------------------------------------
    # LIVE: Prometheus, не зависит от выбранного run
    # ------------------------------------------------------------------
    panels.append(row(
        pid,
        "LIVE: текущий прогон (Prometheus, обновление 10 с; не зависит от выбранного «Прогон»)",
        y,
    ))
    pid += 1
    y += 1

    panels.append(prom_table(
        pid, "LIVE: текущий прогон (No data = сейчас ничего не запущено)",
        f'viday_loadtest_info{{workload="{wl}"}}',
        y, h=7, w=8,
    ))
    pid += 1

    rps_live = prom_ts_multi(
        pid, "LIVE: RPS и VU (генератор)", [
            (live_rps, "{{run_id}} · RPS"),
            (live_vus, "{{run_id}} · VU"),
        ], {"h": 7, "w": 16, "x": 8, "y": y}, "reqps", "RPS / VU", 1,
    )
    rps_live["fieldConfig"]["overrides"] += [
        {"matcher": {"id": "byRegexp", "options": ".* · VU$"},
         "properties": [
             {"id": "unit", "value": "short"},
             {"id": "custom.axisPlacement", "value": "right"},
             {"id": "custom.axisLabel", "value": "VU"},
             {"id": "color", "value": {"mode": "fixed", "fixedColor": "orange"}},
         ]},
    ]
    panels.append(rps_live)
    pid += 1
    y += 7

    lat_live = prom_ts_multi(
        pid, "LIVE: p95/p99 задержки (генератор)", [
            (live_p95, "{{run_id}} · p95"),
            (live_p99, "{{run_id}} · p99"),
        ], {"h": 7, "w": 8, "x": 0, "y": y}, "ms", "мс", 1,
    )
    lat_live["fieldConfig"]["defaults"]["custom"]["scaleDistribution"] = {
        "type": "log", "log": 10,
    }
    panels.append(lat_live)
    pid += 1

    panels.append(prom_ts_multi(
        pid, "LIVE: отказы/с по видам (генератор)", [
            (live_errors, "{{run_id}} · {{kind}}"),
        ], {"h": 7, "w": 8, "x": 8, "y": y}, "reqps", "ошибок/с", 2,
    ))
    pid += 1

    panels.append(prom_ts(
        pid, "LIVE: CPU API, % от лимита контейнера", live_cpu,
        {"h": 7, "w": 8, "x": 16, "y": y}, "percent", "%", 1,
        legend_format="{{run_id}} · api",
    ))
    pid += 1
    y += 7

    panels.append(prom_ts(
        pid, "LIVE: RAM API, MiB (working set)", live_ram,
        {"h": 7, "w": 8, "x": 0, "y": y}, "mbytes", "MiB", 1,
        legend_format="{{run_id}} · api",
    ))
    pid += 1

    panels.append(prom_ts(
        pid, "LIVE: JVM heap, MiB (Actuator)", live_heap,
        {"h": 7, "w": 8, "x": 8, "y": y}, "mbytes", "MiB", 1,
        legend_format="heap",
    ))
    pid += 1

    panels.append(prom_ts_multi(
        pid, "LIVE: HTTP приложения — RPS и 5xx/с", [
            (live_http, "HTTP RPS"),
            (live_5xx, "HTTP 5xx/с"),
        ], {"h": 7, "w": 8, "x": 16, "y": y}, "reqps", "", 2,
    ))
    pid += 1
    y += 7

    # ------------------------------------------------------------------
    # Сводка по прогонам этого типа
    # ------------------------------------------------------------------
    panels.append(row(pid, "Прогоны выбранного типа: клик по run_id зумит таймпикер на его интервал", y))
    pid += 1
    y += 1
    panels.append(table(
        pid,
        "Прогоны в базе (только выбранный «Тип прогона»)",
        RUNS_SQL, y, h=10, w=24, overrides=runs_overrides(),
    ))
    pid += 1
    y += 10
    panels.append(table(pid, "Аппаратная конфигурация: процессор и память", HARDWARE_SQL, y, h=7, w=12))
    pid += 1
    panels.append(table(pid, "Конфигурация нагрузки: корутины, потоки и критерий отказа",
                        LOAD_CONFIG_SQL, y, h=7, w=12))
    panels[-1]["gridPos"]["x"] = 12
    pid += 1
    y += 7

    # ------------------------------------------------------------------
    # KPI выбранного прогона
    # ------------------------------------------------------------------
    panels.append(row(pid, "Ключевые показатели выбранного прогона", y))
    pid += 1
    y += 1
    kpi_where = f"WHERE run_id = {RUN} AND scope = 'overall' AND operation = 'all'"
    stats = [
        ("Запросы", f'SELECT sum(requests)::bigint FROM lt_scenario_summary {kpi_where}', "short", 0),
        ("RPS (средний)", f'SELECT round((sum(requests) / NULLIF(sum(duration_s),0))::numeric, 1) FROM lt_scenario_summary {kpi_where}', "reqps", 1),
        ("p95 (макс. фаза), мс", f'SELECT round(max(p95_ms)::numeric, 1) FROM lt_scenario_summary {kpi_where}', "ms", 1),
        ("p99 (макс. фаза), мс", f'SELECT round(max(p99_ms)::numeric, 1) FROM lt_scenario_summary {kpi_where}', "ms", 1),
        ("Отказы 5xx/timeout, %", f'SELECT round((sum(errors_unexpected)::double precision / NULLIF(sum(requests),0) * 100)::numeric, 2) FROM lt_scenario_summary {kpi_where}', "percent", 2),
        ("Инъекции 4xx, %", f'SELECT round((sum(errors_injected)::double precision / NULLIF(sum(requests),0) * 100)::numeric, 2) FROM lt_scenario_summary {kpi_where}', "percent", 2),
    ]
    for i, (title, sql, unit, dec) in enumerate(stats):
        panels.append(stat(pid, title, sql, {"h": 4, "w": 4, "x": i * 4, "y": y}, unit, dec))
        pid += 1
    y += 4

    # ------------------------------------------------------------------
    # Отказы: когда именно сервис перестал справляться
    # ------------------------------------------------------------------
    panels.append(row(pid, "Отказы: пик, окна выше порога и первое падение", y))
    pid += 1
    y += 1
    panels.append(stat(pid, "Пик отказов в 5-с окне, %", PEAK_FAILURE_SQL,
                       {"h": 5, "w": 8, "x": 0, "y": y}, "percent", 2))
    pid += 1
    panels.append(stat(pid, "5-с окон с откзами ≥95%", FAILURE_WINDOWS_SQL,
                       {"h": 5, "w": 8, "x": 8, "y": y}, "short", 0))
    pid += 1
    panels.append(stat(pid, "Первый таймстемп окна, где отказов больше успехов", CROSS_SQL,
                       {"h": 5, "w": 8, "x": 16, "y": y}, "none", 0))
    pid += 1
    y += 5

    # ------------------------------------------------------------------
    # Главный график: VU vs RPS + задержки/отказы
    # ------------------------------------------------------------------
    panels.append(row(pid, "Нагрузка во времени: VU → RPS, задержки, отказы и доля отказов", y))
    pid += 1
    y += 1

    vu_rps = ts(
        pid, "VU и RPS во времени: рост пользователей и фактический поток запросов",
        RPS_SQL, {"h": 9, "w": 24, "x": 0, "y": y}, "reqps", "RPS / VU", 1,
        legend_opts={"showLegend": True, "displayMode": "table", "placement": "bottom",
                     "calcs": ["max", "mean", "lastNotNull"]},
    )
    vu_rps["fieldConfig"]["overrides"] += [
        {"matcher": {"id": "byName", "options": "VU"},
         "properties": [
             {"id": "unit", "value": "short"},
             {"id": "custom.axisPlacement", "value": "right"},
             {"id": "custom.axisLabel", "value": "VU"},
             {"id": "custom.lineWidth", "value": 2},
             {"id": "color", "value": {"mode": "fixed", "fixedColor": "orange"}},
         ]},
        {"matcher": {"id": "byName", "options": "RPS"},
         "properties": [
             {"id": "custom.lineWidth", "value": 2},
             {"id": "color", "value": {"mode": "fixed", "fixedColor": "green"}},
         ]},
    ]
    panels.append(vu_rps)
    pid += 1
    y += 9

    panels.append(long_ts(
        pid, "Перцентили p50/p95/p99 во времени (log-шкала)", LATENCY_SQL,
        {"h": 8, "w": 12, "x": 0, "y": y}, "ms", "мс", 2, log=True,
    ))
    pid += 1
    panels.append(ts(pid, "Отказы, инъекции и бизнес-4xx в 5-с окне",
                     ERRORS_SQL, {"h": 8, "w": 12, "x": 12, "y": y}, "short", "ошибок", 0))
    pid += 1
    y += 8

    failure_rate = ts(
        pid, "Доля отказов в 5-с окне, % (порог 95% = критерий остановки)",
        FAILURE_RATE_SQL, {"h": 8, "w": 12, "x": 0, "y": y}, "percent", "%", 2,
    )
    failure_rate["fieldConfig"]["defaults"]["thresholds"] = {
        "mode": "absolute",
        "steps": [
            {"color": "green", "value": None},
            {"color": "yellow", "value": 1},
            {"color": "red", "value": 95},
        ],
    }
    failure_rate["fieldConfig"]["defaults"]["custom"]["thresholdsStyle"] = {"mode": "line"}
    panels.append(failure_rate)
    pid += 1
    panels.append(ts(pid, "Накопленные успешные операции и отказы от начала прогона",
                     CUMULATIVE_COMPLETION_SQL, {"h": 8, "w": 12, "x": 12, "y": y},
                     "short", "операций", 0))
    pid += 1
    y += 8

    # ------------------------------------------------------------------
    # Адаптивные детали сценария
    # ------------------------------------------------------------------
    panels.append(row(
        pid,
        "Детали сценария (адаптивно): ramp → ступени VU; stress/soak → фазы и время восстановления",
        y,
    ))
    pid += 1
    y += 1
    panels.append(table(pid, "Ступени ramp или фазы stress/soak", ADAPTIVE_DETAILS_SQL, y, h=10, w=24))
    pid += 1
    y += 10

    # ------------------------------------------------------------------
    # Распределения
    # ------------------------------------------------------------------
    panels.append(row(pid, "Распределение задержек: CDF и гистограмма (X — таймстемпы)", y))
    pid += 1
    y += 1
    panels.append(ts(
        pid, "CDF во времени: доля запросов ≤ порога", CDF_TS_SQL,
        {"h": 9, "w": 12, "x": 0, "y": y}, "percentunit", "доля запросов", 3,
    ))
    pid += 1
    panels.append(panel(
        pid, "heatmap", "Гистограмма задержки во времени (Y — мс, цвет — запросы)",
        {"h": 9, "w": 12, "x": 12, "y": y},
        target(HIST_TS_SQL, fmt="time_series"),
        field_config({"unit": "short", "decimals": 0}),
        {"calculate": False,
         "color": {"mode": "scheme", "scheme": "Turbo"},
         "yAxis": {"unit": "ms", "axisPlacement": "left"},
         "xAxis": {"unit": "time"},
         "legend": {"show": True},
         "tooltip": {"mode": "single", "sort": "none"}},
    ))
    pid += 1
    y += 9

    # ------------------------------------------------------------------
    # Операции
    # ------------------------------------------------------------------
    panels.append(row(pid, "Пользовательские операции: где именно тормозит", y))
    pid += 1
    y += 1
    panels.append(table(
        pid,
        "Сводка по операциям: запросы, RPS, p50/p95/p99, отказы",
        OPERATIONS_SQL, y, h=9, w=14,
    ))
    pid += 1
    panels.append(panel(
        pid, "table", "Топ-15 операций по p95", {"h": 9, "w": 10, "x": 14, "y": y},
        target(TOP_OPS_SQL), table_fc(),
        {"showHeader": True, "cellHeight": "sm",
         "footer": {"show": False, "reducer": ["sum"], "countRows": False, "fields": ""}},
    ))
    pid += 1
    y += 9

    # ------------------------------------------------------------------
    # Ресурсы контейнеров
    # ------------------------------------------------------------------
    panels.append(row(pid, "Ресурсы контейнеров (exporter → PostgreSQL, шаг ~10 с)", y))
    pid += 1
    y += 1
    panels.append(resource_ts(pid, "CPU по компонентам, % (100% = все ядра контейнера)",
                              RESOURCE_CPU_SQL,
                              {"h": 8, "w": 12, "x": 0, "y": y}, "percent", "%", 2))
    pid += 1
    panels.append(resource_ts(pid, "RAM working set по компонентам, MiB",
                              RESOURCE_RAM_SQL,
                              {"h": 8, "w": 12, "x": 12, "y": y}, "mbytes", "MiB", 1))
    pid += 1
    y += 8
    for i, (title, sql) in enumerate([
        ("Чтение с диска, MiB/s", RESOURCE_DISK_READ_SQL),
        ("Запись на диск, MiB/s", RESOURCE_DISK_WRITE_SQL),
        ("Сеть: приём, MiB/s", RESOURCE_NET_RX_SQL),
        ("Сеть: передача, MiB/s", RESOURCE_NET_TX_SQL),
    ]):
        panels.append(resource_ts(pid, title, sql,
                                  {"h": 8, "w": 6, "x": i * 6, "y": y}, "mbytes", "MiB/s", 2))
        pid += 1
    y += 8

    # ------------------------------------------------------------------
    # JVM приложения
    # ------------------------------------------------------------------
    panels.append(row(pid, "JVM приложения (Actuator → PostgreSQL): история выбранного прогона", y))
    pid += 1
    y += 1
    for i, (title, sql) in enumerate([
        ("JVM heap used, MiB", HEAP_SQL),
        ("GC pause, с/с (сумма по сборщикам)", GC_SQL),
        ("HikariCP: активные / idle соединения", HIKARI_SQL),
        ("HTTP RPS и 5xx/с по Actuator (без /actuator)", APP_HTTP_SQL),
    ]):
        panels.append(ts(pid, title, sql,
                         {"h": 7, "w": 6, "x": i * 6, "y": y},
                         "mbytes" if i == 0 else ("s" if i == 1 else ("short" if i == 2 else "reqps")),
                         "MiB" if i == 0 else ("с/с" if i == 1 else ""), 3 if i == 1 else 1))
        pid += 1
    y += 7
    panels.append(ts(pid, "Потоки приложения и запросы в полёте",
                     THREADS_SQL, {"h": 7, "w": 12, "x": 0, "y": y}, "short", "потоки", 0))
    pid += 1
    panels.append(ts(pid, "Tomcat: активные сессии", TOMCAT_ACTIVE_SQL,
                     {"h": 7, "w": 6, "x": 12, "y": y}, "short", "сессий", 0))
    pid += 1
    panels.append(ts(pid, "Tomcat: отклонённые сессии, /с", TOMCAT_REJECTED_SQL,
                     {"h": 7, "w": 6, "x": 18, "y": y}, "reqps", "сессий/с", 3))
    pid += 1
    y += 7

    # ------------------------------------------------------------------
    # Ресурсы min/max/median
    # ------------------------------------------------------------------
    panels.append(row(pid, "Ресурсы: min / max / median по компонентам", y))
    pid += 1
    y += 1
    panels.append(table(pid, "CPU / RAM / диск / сеть: min-max-median за весь прогон",
                        RESOURCE_SUMMARY_SQL, y, h=9, w=24))
    pid += 1
    y += 9
    panels.append(table(pid, "Ресурсы по фазам прогона", RESOURCE_PHASES_SQL, y, h=10, w=24))
    pid += 1
    y += 10

    # ------------------------------------------------------------------
    # Конфигурация, окружение, выводы
    # ------------------------------------------------------------------
    panels.append(row(pid, "Конфигурация, окружение и автоматические выводы", y))
    pid += 1
    y += 1
    panels.append(table(pid, "Конфигурация прогона (metadata.config)", CONFIG_SQL, y, h=9, w=12))
    pid += 1
    panels.append(table(pid, "Окружение и CPU-изоляция (metadata.environment)", ENV_SQL, y, h=9, w=12))
    panels[-1]["gridPos"]["x"] = 12
    pid += 1
    y += 9
    panels.append(table(pid, "Автоматические выводы: стабильная зона, восстановление, тренд RAM",
                        FINDINGS_SQL, y, h=8, w=18))
    pid += 1
    panels.append(text_panel(
        pid, "Ссылки",
        "### Отчёты\n\n"
        "- [ЛР3 — микробенчмарк](/d/benchmark-lr3)\n"
        "- [Prometheus](http://localhost:9090)\n"
        "- Markdown-отчёт: `docs/lr4.md`, результаты: `docs/lr4-results/<run-id>/`\n\n"
        "### Что считается ошибкой\n\n"
        "- **отказы** — 5xx, timeout, обрыв (входят в error rate SLO);\n"
        "- **инъекции 4xx** — намеренно ошибочные операции (10%/20% тесты);\n"
        "- **прочие 4xx** — бизнес-ответы сценария (409 «уже подписан» и т.п.).\n",
        {"h": 8, "w": 6, "x": 18, "y": y},
    ))
    pid += 1

    return panels


def main():
    # Без DASHBOARD_WORKLOAD генерируем обе доски в дочерних процессах: SQL
    # собирается на уровне модуля, поэтому профиль фиксируется через окружение.
    if not os.environ.get("DASHBOARD_WORKLOAD"):
        for workload in WORKLOADS:
            env = {**os.environ, "DASHBOARD_WORKLOAD": workload}
            subprocess.run([sys.executable, os.path.abspath(__file__)], env=env, check=True)
        return

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(
        root, "docker", "grafana", "provisioning", "dashboards",
        f"loadtest-{WORKLOAD}.json",
    )
    os.makedirs(os.path.dirname(out), exist_ok=True)

    dashboard = {
        "id": None,
        "uid": DASHBOARD_UID,
        "title": f"ЛР4: {WORKLOAD} — нагрузочное тестирование vidayapi (ramp/stress/soak)",
        "tags": ["lr4", "loadtest", "coroutines", WORKLOAD],
        "timezone": "browser",
        "editable": True,
        "graphTooltip": 1,
        "schemaVersion": 39,
        "refresh": "10s",
        # 24 часа: live-панели читают Prometheus и показывают текущий прогон;
        # старые прогоны выбираются кликом по run_id в таблице (она вне времени).
        "time": {"from": "now-24h", "to": "now"},
        "timepicker": {"hidden": False},
        "templating": {
            "list": [
                scenario_var(),
                query_var(
                    "run", "Прогон",
                    "SELECT r.run_id AS \"__value\", "
                    "r.run_id || ' · CPU=' || COALESCE(r.config->>'api_cpus', '?')"
                    " || ' · RAM=' || COALESCE(r.config->>'api_ram_mb', '?') || 'MiB'"
                    " || ' · co=' || COALESCE(r.config->>'coroutines', '-')"
                    " || CASE WHEN COALESCE(r.config->>'early_stopped', 'false')::boolean"
                    "         THEN ' · досрочно' ELSE '' END AS \"__text\" "
                    "FROM lt_runs r WHERE "
                    f"COALESCE(r.config->>'workload', 'auth') = '{WORKLOAD}' "
                    "AND r.scenario = '$scenario' "
                    "ORDER BY r.started_at DESC",
                    refresh=2,
                    description=f"Прогоны только выбранного типа и workload={WORKLOAD}. "
                                "Список отсортирован от новых к старым; при открытии выбирается "
                                "самый свежий. Обновляется при сдвиге таймпикера.",
                ),
            ]
        },
        "annotations": {"list": []},
        "links": [
            {
                "title": "Последние 2 часа",
                "url": f"/d/{DASHBOARD_UID}?from=now-2h&to=now",
                "type": "link",
                "icon": "clock",
                "targetBlank": False,
                "keepTime": False,
                "includeVars": False,
                "tooltip": "Показать текущий/свежий прогон на оси в 2 часа",
            },
            {
                "title": "Весь архив (90 дней)",
                "url": f"/d/{DASHBOARD_UID}?from=now-90d&to=now",
                "type": "link",
                "icon": "history",
                "targetBlank": False,
                "keepTime": False,
                "includeVars": False,
                "tooltip": "Показать старые прогоны; затем кликните run_id в таблице",
            },
            {
                "title": f"ЛР4: {OTHER_WORKLOAD}",
                "url": f"/d/loadtest-lr4-{OTHER_WORKLOAD}",
                "type": "link",
                "icon": "dashboard",
                "targetBlank": False,
                "keepTime": True,
                "includeVars": False,
                "tooltip": f"Перейти к доске сценария {OTHER_WORKLOAD}",
            },
            {
                "title": "ЛР3: микробенчмарк",
                "url": "/d/benchmark-lr3",
                "type": "link",
                "icon": "external link",
                "targetBlank": False,
                "keepTime": True,
                "includeVars": False,
                "tooltip": "Вернуться к дашборду ЛР3",
            },
        ],
        "panels": build_panels(),
    }

    payload = {"dashboard": dashboard, "overwrite": True, "inputs": [], "folderId": 0}
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print(f"OK: {out} ({len(dashboard['panels'])} панелей)")


if __name__ == "__main__":
    main()
