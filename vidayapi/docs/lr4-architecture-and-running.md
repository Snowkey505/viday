# ЛР4: архитектура тестов, запуск и последние изменения

Краткий путь по коду и командам. Полная методика — в [lr4.md](lr4.md), параметры — в
[lr4-loadtest-configuration.md](lr4-loadtest-configuration.md), чтение панелей — в
[lr4-grafana-guide.md](lr4-grafana-guide.md).

## Что изменено

- CPU во всех новых отчётах нормализован к эффективному лимиту контейнера: 100% означает
  все доступные ему ядра. Учитываются одновременно cpuset, Docker NanoCPUs и cgroup quota.
- Снимки JVM, потоков, HTTP, HikariCP и Tomcat сохраняются в PostgreSQL. Их отображение
  больше не зависит от retention Prometheus и выбранного диапазона времени Grafana.
- Добавлены структурированные таблицы CPU/RAM/heap, корутин и потоков; исправлены зависимые
  переключатели прогона, фазы, операции и перцентилей.
- Графики успеха/отказов показывают весь прогон: отдельно темп в каждом 5-с окне и
  накопленные количества. Первое пересечение обозначено числом секунд или «не достигнуто».
- Добавлен `WORKLOAD=public`: реальные публичные запросы без BCrypt-логина. Старый
  `WORKLOAD=auth` сохранён без изменений.
- Критический отказ останавливает прогон досрочно вместо ожидания всего длительного плана.

## Где находится нагрузочный генератор

Основной генератор — **собственный Kotlin/Java 17**, а не k6. Модуль Gradle:
`loadtest/build.gradle.kts`; точка входа — [Main.kt](../loadtest/src/main/kotlin/com/vidayapi/loadtest/Main.kt).

| Часть | Файл | Ответственность |
|---|---|---|
| Конфигурация | [LoadConfig.kt](../loadtest/src/main/kotlin/com/vidayapi/loadtest/LoadConfig.kt) | Читает `WORKLOAD`, `COROUTINES`, `DISPATCHER_THREADS`, timeout, длительности и критерий отказа из env |
| Диспетчер | [core/LoadDispatcher.kt](../loadtest/src/main/kotlin/com/vidayapi/loadtest/core/LoadDispatcher.kt) | Наследник `CoroutineDispatcher`; собственный `Executors.newFixedThreadPool`, потоки `lr4-dispatcher-*` |
| Временной план | [core/LoadPlan.kt](../loadtest/src/main/kotlin/com/vidayapi/loadtest/core/LoadPlan.kt) | `ramp`, `stress`, постоянная нагрузка, число активных VU |
| Запуск корутин | [LoadRunner.kt](../loadtest/src/main/kotlin/com/vidayapi/loadtest/LoadRunner.kt) | Создаёт `COROUTINES` workers через `launch(dispatcher)`, планировщик меняет VU и проверяет остановку |
| Пользовательские сценарии | [UserSession.kt](../loadtest/src/main/kotlin/com/vidayapi/loadtest/UserSession.kt) | `auth`: login и защищённые операции; `public`: feed, video detail, live streams |
| HTTP и потоки I/O | [http/LoadHttpClient.kt](../loadtest/src/main/kotlin/com/vidayapi/loadtest/http/LoadHttpClient.kt) | Асинхронный `HttpClient.sendAsync` на executor диспетчера; измеряет latency и status |
| Детектор отказа | [core/FailureDetector.kt](../loadtest/src/main/kotlin/com/vidayapi/loadtest/core/FailureDetector.kt) | Полные смежные временные окна и критерий ранней остановки |
| Сырые точки | [core/PointWriter.kt](../loadtest/src/main/kotlin/com/vidayapi/loadtest/core/PointWriter.kt) | Потокобезопасный NDJSON в формате k6 Point |

Число корутин не равно числу потоков. Корутины представляют параллельные пользовательские
сессии; во время HTTP I/O поток не блокируется благодаря `sendAsync` и
`suspendCancellableCoroutine`. Потоки диспетчера выполняют готовые continuation. Поэтому
можно сравнивать, например, 100 и 300 корутин при неизменных двух потоках.

k6 оставлен как legacy-генератор для auth-методики: сценарий — [loadtest/scenario.js](../loadtest/scenario.js),
образ — [docker/loadtest/Dockerfile](../docker/loadtest/Dockerfile). Он не поддерживает
`WORKLOAD=public`; такая комбинация намеренно отклоняется, чтобы k6 не выполнил login незаметно.

## Docker, анализ и Grafana

1. [Makefile](../Makefile) принимает параметры и вызывает
   [scripts/run-loadtest.sh](../scripts/run-loadtest.sh).
2. Скрипт собирает образы, создаёт изолированный Compose-проект по
   [docker-compose.loadtest.yml](../docker/docker-compose.loadtest.yml), закрепляет CPU,
   ограничивает RAM и передаёт конфигурацию генератору.
3. [exporter.py](../docker/loadtest/exporter/exporter.py) каждые 5 с читает Docker stats и
   `/actuator/prometheus`, пишет `container-samples.jsonl` и `app-samples.jsonl`.
   Exporter и API подключены к общей внешней сети `viday_loadtest_net`; обращение идёт по
   адресу этой сети, а не через недоступный из контейнера IP изолированной Compose-сети.
   Оркестратор дополнительно запрашивает `/collect-application` до warmup и после генератора:
   даже короткий early-stop имеет как минимум начальный Actuator-снимок.
4. [analyze-loadtest.py](../scripts/analyze-loadtest.py) считает RPS, p50/p75/p90/p95/p99/p99.7,
   CDF, гистограммы, ряды по операциям и ресурсам, нормализует CPU и создаёт `report.json`.
5. [import-loadtest-to-db.py](../scripts/import-loadtest-to-db.py) идемпотентно сохраняет прогон
   в таблицы `lt_*`; [02_loadtest_schema.sql](../docker/grafana/init/02_loadtest_schema.sql)
   описывает схему, включая `lt_app_samples`.
6. [generate-loadtest-dashboard.py](../scripts/generate-loadtest-dashboard.py) создаёт две
   доски: `loadtest-lr4-auth` и `loadtest-lr4-public` — по одной на сценарий, чтобы auth и
   public не смешивались. Все отчётные панели читают PostgreSQL; ось X — реальные
   таймстемпы (`bucket_ts`/`ts`), поэтому прогоны одного `WORKLOAD` можно сравнивать на
   общей шкале времени, выбирая диапазон таймпикером. Live-панели параллельно читают
   Prometheus: контейнерные ресурсы, Actuator и `/metrics` Kotlin-генератора.

Отчёты остаются в `loadtest/results/<run-id>/`, компактные копии — в
`docs/lr4-results/<run-id>/`. Постоянная история Grafana находится в Docker-томе
`bench_postgres_data`; разные `run_id` не перезаписывают друг друга.

## Команды запуска

Поднять визуализацию:

```bash
make grafana-up
```

Короткая проверка auth и public:

```bash
make loadtest SCENARIO=smoke WORKLOAD=auth CPU=1 RAM=512 \
  COROUTINES=20 THREADS=2 SMOKE_VUS=8 SMOKE_DURATION=20s \
  WARMUP=5s REQUEST_TIMEOUT=2s

make loadtest SCENARIO=smoke WORKLOAD=public CPU=1 RAM=512 \
  COROUTINES=20 THREADS=2 SMOKE_VUS=8 SMOKE_DURATION=20s \
  WARMUP=5s REQUEST_TIMEOUT=2s
```

Один короткий public-ramp с ростом нагрузки и автоматической остановкой при критическом отказе:

```bash
make loadtest SCENARIO=ramp WORKLOAD=public CPU=1 RAM=512 \
  COROUTINES=300 THREADS=2 RAMP_MAX_VUS=300 \
  RAMP_STEP_DURATION=5s RAMP_PLATEAU_DURATION=20s \
  REQUEST_TIMEOUT=2s WARMUP=5s THINK_MIN_MS=50 THINK_MAX_MS=100
```

Матрица CPU × RAM × корутин (прогоны выполняются последовательно):

```bash
RUN_PREFIX=lr4-v2-matrix \
RAMP_MAX_VUS=300 RAMP_STEP_DURATION=5s RAMP_PLATEAU_DURATION=20s \
WARMUP=5s REQUEST_TIMEOUT=2s THREADS=2 THINK_MIN_MS=50 THINK_MAX_MS=100 \
make loadtest-matrix CPUS="1 2" RAMS="512 1024" \
  COROUTINES="100 300" SCENARIO=ramp WORKLOAD=auth
```

Та же матрица без логина: добавьте/замените `WORKLOAD=public`. Не запускайте две матрицы
параллельно: они используют одни cpuset и искажали бы измерения друг друга.

Проверки без нагрузки:

```bash
./gradlew :loadtest:test --no-daemon --console=plain
python3 -m py_compile docker/loadtest/exporter/exporter.py \
  scripts/analyze-loadtest.py scripts/import-loadtest-to-db.py \
  scripts/generate-loadtest-dashboard.py
make loadtest-dashboard
python3 scripts/probe-grafana-panels.py <run-id> <scenario> [workload]
python3 scripts/test-dashboard-queries.py
```

Grafana: <http://localhost:3000/d/loadtest-lr4-auth> (auth/матрикс) и
<http://localhost:3000/d/loadtest-lr4-public> (public), `admin`/`admin`.

## Критерий «сервер лёг»

По умолчанию `EARLY_STOP=1`, `EARLY_STOP_AFTER=30s`, `FAILURE_WINDOW=5s`,
`FAILURE_WINDOWS=3`, `FAILURE_MIN_REQUESTS=20`, `FAILURE_THRESHOLD=0.95`.

Остановка происходит не раньше 30 секунд и только при трёх **смежных полных** 5-секундных
окнах, в каждом из которых завершилось не менее 20 запросов и не менее 95% завершились
неожиданным отказом (5xx, timeout, обрыв). То есть успехов не более 5%, а отказов минимум
в 19 раз больше успешных операций. Пустое окно само по себе не считается доказательством:
для него нужны завершившиеся измерения. Статус становится `early_stopped`, причина и все
точки до остановки сохраняются.

Параметры можно изменить через одноимённые переменные Makefile. `EARLY_STOP=0` отключает
остановку для диагностики, но для сравнительной матрицы его менять не следует.

## Проверенные прогоны

- `lr4-v2-smoke-auth`: 20 с, 1 840 запросов, 92,5 RPS, p95 485,5 мс, 0 отказов.
- `lr4-v2-smoke-public`: 20 с, 5 478 запросов, 274,4 RPS, p95 11,0 мс, 0 отказов.
  В отчёте только `feed`, `video_detail`, `live_streams` — BCrypt-логина действительно нет.
- `lr4-v2-public-c1-r512-co300`: 64,4 с, 70 146 запросов, 1 089,9 RPS, p95 269,8 мс,
  0 отказов, 11 Actuator-снимков, максимум нормализованного CPU 100%. Статус `completed`,
  а не `early_stopped`: выбранный максимум 300 VU сервер выдержал, поэтому пересечение
  кривых не наступило. Нагрузку сверх заданного плана искусственно не увеличивали.
- `lr4-v2-matrix-*`: восемь auth-ramp прогонов длительностью 30,8–63,3 с, все с
  Actuator-снимками и `early_stopped` по критерию 95%. Числа приведены в таблице
  [конфигураций](lr4-loadtest-configuration.md#актуальная-матрица-lr4-v2-matrix).

Результаты хранятся в `docs/lr4-results/<run-id>/` и доступны в переключателе прогонов
Grafana. Полная короткая матрица `lr4-v2-matrix-*` описана в
[lr4-loadtest-configuration.md](lr4-loadtest-configuration.md).
