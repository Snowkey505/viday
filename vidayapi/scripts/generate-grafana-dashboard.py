#!/usr/bin/env python3
"""Генератор дашборда Grafana для ЛР3 (benchmark.json).

Переносит в Grafana те же комбинированные графики, что в SVG-отчёте
(docs/lr3-charts/<run>/report.html, «оба метода на одном графике»):

  • временной ряд, CDF, гистограмма — на каждом графике ДВЕ серии
    разных цветов: Jackson 3 (синий) и kotlinx.serialization (зелёный);
  • столбцы выбранных перцентилей (как compare-p50-bars / compare-p99-bars);
  • на latency и CDF — асимптоты перцентилей для обеих библиотек.
    p50 и p95 всегда, остальные — если отмечены в переключателе percentile.

Почему не long-формат (колонка library): Grafana trend/timeseries берёт
все числовые колонки как Y — строковое поле library серии не создаёт.
Поэтому запросы в wide-формате (колонки jackson / kotlinx). CDF — тот же
trend, что и latency: X = latency_ns, вертикали перцентилей — две точки
(y=0 и y=1) на каждом выбранном перцентиле.

Управление:
  • model (x1 / x4 / x8) — модель полезной нагрузки для overlay-графиков;
  • percentile (мультивыбор p50 / p75 / p90 / p95 / p99) — столбчатое
    сравнение и дополнительные асимптоты на latency/CDF (p50 и p95 всегда).

Запуск:  python3 scripts/generate-grafana-dashboard.py
"""
import json
import os

DS = {"type": "grafana-postgresql-datasource", "uid": "benchmark_pg"}

COLOR_JACKSON = "#1f77b4"
COLOR_KOTLINX = "#2ca02c"
LATEST_RUN = "(SELECT run_id FROM benchmark_runs ORDER BY generated_at DESC LIMIT 1)"
DIRECTIONS = ["read", "write", "mixed"]
PERCENTILES = [
    ("p50", "p50_ns", 0.50, {"fill": "dot", "dash": [2, 4]}),
    ("p75", "p75_ns", 0.75, {"fill": "dash", "dash": [4, 3]}),
    ("p90", "p90_ns", 0.90, {"fill": "dash", "dash": [8, 4]}),
    ("p95", "p95_ns", 0.95, {"fill": "dash", "dash": [10, 4]}),
    ("p99", "p99_ns", 0.99, {"fill": "dash", "dash": [16, 4]}),
]
LIB_META = (
    ("jackson", "Jackson 3", COLOR_JACKSON),
    ("kotlinx", "kotlinx.serialization", COLOR_KOTLINX),
)

TEXT_CONTENT = """## ЛР3: микробенчмарк — Jackson 3 vs kotlinx.serialization

Сравниваются **две библиотеки JSON** на одной задаче:
- **Jackson 3** (синий) — runtime-рефлексия, production vidayapi;
- **kotlinx.serialization** (зелёный) — сериализация на этапе компиляции.

**Модель** (переключатель сверху): **x1** 10 полей / **x4** 40 / **x8** 80.
Сценарии: **read** (JSON→объект), **write** (объект→JSON), **mixed** (чтение+запись).
Методика: warmup → settle → measure → teardown, batch=256, 1 ядро CPU.

На каждом графике **обе библиотеки наложены** (как SVG overlay в report.html):
- **Временной ряд** — X: номер сэмпла, Y: нс/операция. Горизонтальные асимптоты — перцентили каждой библиотеки.
- **CDF** — X: латентность нс/оп, Y: доля запросов. Чем левее кривая — тем быстрее. Вертикальные асимптоты — те же перцентили.
- **Гистограмма** — общие бины по диапазону обеих серий, столбцы рядом.
- **Перцентили** — мультивыбор **percentile**. На latency/CDF **p50 и p95 всегда**; p75/p90/p99 — если добавить сверху. Столбцы сравнивают выбранные значения.
"""

# p50 и p95 всегда входят в wanted_pcts; остальные — из мультивыбора ${percentile}.
WANTED_PCTS_CTE = """
wanted_pcts AS (
  SELECT DISTINCT trim(both FROM x) AS p
  FROM unnest(ARRAY['p50','p95'] || string_to_array('${percentile:csv}', ',')) AS t(x)
  WHERE trim(both FROM x) <> ''
)
""".strip()


def target(raw_sql, ref="A", max_data_points=4000):
    return {
        "refId": ref,
        "datasource": DS,
        "editorMode": "code",
        "format": "table",
        "rawQuery": True,
        "rawSql": raw_sql.strip(),
        "maxDataPoints": max_data_points,
    }


def library_overrides(extra_jackson=None, extra_kotlinx=None):
    jackson_props = [
        {"id": "displayName", "value": "Jackson 3"},
        {"id": "color", "value": {"mode": "fixed", "fixedColor": COLOR_JACKSON}},
    ]
    kotlinx_props = [
        {"id": "displayName", "value": "kotlinx.serialization"},
        {"id": "color", "value": {"mode": "fixed", "fixedColor": COLOR_KOTLINX}},
        {"id": "custom.lineStyle", "value": {"fill": "dash", "dash": [6, 3]}},
    ]
    if extra_jackson:
        jackson_props.extend(extra_jackson)
    if extra_kotlinx:
        kotlinx_props.extend(extra_kotlinx)
    return [
        {"matcher": {"id": "byName", "options": "jackson"}, "properties": jackson_props},
        {"matcher": {"id": "byName", "options": "kotlinx"}, "properties": kotlinx_props},
    ]


def percentile_line_overrides(extra=None):
    extra = extra or []
    overrides = []
    for lib, title, color in LIB_META:
        for p, _col, _frac, dash in PERCENTILES:
            overrides.append({
                "matcher": {"id": "byName", "options": f"{lib}_{p}"},
                "properties": [
                    {"id": "displayName", "value": f"{title} {p}"},
                    {"id": "color", "value": {"mode": "fixed", "fixedColor": color}},
                    {"id": "custom.lineStyle", "value": dash},
                    {"id": "custom.lineWidth", "value": 1},
                    {"id": "custom.fillOpacity", "value": 0},
                    {"id": "custom.showPoints", "value": "never"},
                    *extra,
                ],
            })
    return overrides


def line_trend_config(axis_label, decimals):
    return field_config(
        {
            "custom": {
                "drawStyle": "line",
                "lineInterpolation": "linear",
                "barAlignment": 0,
                "lineWidth": 1,
                "fillOpacity": 10,
                "gradientMode": "none",
                "spanNulls": True,
                "showPoints": "never",
                "pointSize": 3,
                "stacking": {"mode": "none", "group": "A"},
                "axisPlacement": "auto",
                "axisLabel": axis_label,
                "axisColorMode": "text",
                "scaleDistribution": {"type": "linear"},
                "hideFrom": {"legend": False, "tooltip": False, "viz": False},
            },
            "decimals": decimals,
        },
        library_overrides() + percentile_line_overrides(),
    )


def pct_null_selects():
    return [
        f"NULL::double precision AS {lib}_{p}"
        for p, _col, _frac, _dash in PERCENTILES
        for lib, _title, _color in LIB_META
    ]


def percentile_field_filter(*keep):
    """Оставляет основные серии и асимптоты p50/p95 + выбранные ${percentile}."""
    extras = "|".join(keep)
    prefix = f"{extras}|" if extras else ""
    pattern = (
        f"^({prefix}jackson|kotlinx|(jackson|kotlinx)_(p50|p95|${{percentile:pipe}}))$"
    )
    return [{
        "id": "filterFieldsByName",
        "options": {"include": {"pattern": pattern}},
    }]


def field_config(defaults=None, overrides=None):
    base = {
        "color": {"mode": "palette-classic"},
        "custom": {},
        "mappings": [],
        "thresholds": {
            "mode": "absolute",
            "steps": [{"color": "green", "value": None}],
        },
    }
    if defaults:
        custom = defaults.pop("custom", None)
        base.update(defaults)
        if custom:
            base["custom"] = {**base.get("custom", {}), **custom}
    return {"defaults": base, "overrides": overrides or []}


def legend():
    return {
        "showLegend": True,
        "displayMode": "list",
        "placement": "bottom",
        "calcs": [],
    }


def tooltip_multi():
    return {"mode": "multi", "sort": "none"}


def panel(pid, ptype, title, grid, targets, field_config=None, options=None, extra=None):
    body = {
        "id": pid,
        "type": ptype,
        "title": title,
        "gridPos": grid,
        "datasource": DS,
        "targets": targets if isinstance(targets, list) else [targets],
        "fieldConfig": field_config or {"defaults": {}, "overrides": []},
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


def timeseries_sql(direction):
    line_cols = []
    for p, col, _frac, _dash in PERCENTILES:
        for lib, _title, _color in LIB_META:
            line_cols.append(
                f"MAX(l.{lib}_{p}) AS {lib}_{p}"
            )
    line_select = ",\n       ".join(line_cols)
    case_cols = []
    for p, col, _frac, _dash in PERCENTILES:
        for lib, _title, _color in LIB_META:
            case_cols.append(
                f"(MAX({col}) FILTER (WHERE library = '{lib}'))"
                f" * (SELECT 1 FROM wanted_pcts WHERE p = '{p}' LIMIT 1) AS {lib}_{p}"
            )
    lines_select = ",\n         ".join(case_cols)
    return f"""
WITH {WANTED_PCTS_CTE},
lines AS (
  SELECT {lines_select}
  FROM benchmark_cases
  WHERE run_id = {LATEST_RUN}
    AND direction = '{direction}'
    AND model = '${{model}}'
)
SELECT s.sample_index AS sample,
       MAX(s.ns_per_op) FILTER (WHERE s.library = 'jackson') AS jackson,
       MAX(s.ns_per_op) FILTER (WHERE s.library = 'kotlinx') AS kotlinx,
       {line_select}
FROM benchmark_samples s
CROSS JOIN lines l
WHERE s.run_id = {LATEST_RUN}
  AND s.direction = '{direction}'
  AND s.model = '${{model}}'
GROUP BY s.sample_index
ORDER BY s.sample_index
"""


def cdf_sql(direction):
    """CDF в wide-формате + вертикальные асимптоты перцентилей (как latency, но X = нс/оп).

    Две точки на каждый перцентиль: (latency, 0) и (latency+ε, 1) — почти вертикаль.
    p50/p95 всегда в wanted_pcts; остальные — из переключателя percentile.
    """
    curve_nulls = ",\n       ".join(pct_null_selects())
    unions = []
    for p, col, _frac, _dash in PERCENTILES:
        selects = [
            f"c.{col} + v.dy AS latency_ns",
            "NULL::double precision AS jackson",
            "NULL::double precision AS kotlinx",
        ]
        for p2, _c2, _f2, _d2 in PERCENTILES:
            for lib, _title, _color in LIB_META:
                if p2 == p:
                    selects.append(
                        f"CASE WHEN c.library = '{lib}' THEN v.y END AS {lib}_{p}"
                    )
                else:
                    selects.append(f"NULL::double precision AS {lib}_{p2}")
        unions.append(
            "SELECT "
            + ",\n       ".join(selects)
            + f"""
FROM cases c
CROSS JOIN (VALUES (0.0, 0.0), (1e-6, 1.0)) AS v(dy, y)
WHERE EXISTS (SELECT 1 FROM wanted_pcts WHERE p = '{p}')"""
        )
    return f"""
WITH {WANTED_PCTS_CTE},
cases AS (
  SELECT library, p50_ns, p75_ns, p90_ns, p95_ns, p99_ns
  FROM benchmark_cases
  WHERE run_id = {LATEST_RUN}
    AND direction = '{direction}'
    AND model = '${{model}}'
)
SELECT c.latency_ns,
       MAX(c.fraction) FILTER (WHERE c.library = 'jackson') AS jackson,
       MAX(c.fraction) FILTER (WHERE c.library = 'kotlinx') AS kotlinx,
       {curve_nulls}
FROM benchmark_cdf c
WHERE c.run_id = {LATEST_RUN}
  AND c.direction = '{direction}'
  AND c.model = '${{model}}'
GROUP BY c.latency_ns
UNION ALL
{"\nUNION ALL\n".join(unions)}
ORDER BY 1
"""


def hist_sql(direction):
    return f"""
WITH src AS (
  SELECT library, ns_per_op
  FROM benchmark_samples
  WHERE run_id = {LATEST_RUN}
    AND direction = '{direction}'
    AND model = '${{model}}'
),
b AS (
  SELECT min(ns_per_op) AS lo, max(ns_per_op) AS hi FROM src
),
binned AS (
  SELECT library,
         width_bucket(ns_per_op, (SELECT lo FROM b), (SELECT hi FROM b) + 1e-6, 20) AS bucket
  FROM src
)
SELECT ROUND(((SELECT lo FROM b)
              + (bucket - 0.5) * ((SELECT hi FROM b) - (SELECT lo FROM b)) / 20.0)::numeric, 1) AS latency_ns,
       COUNT(*) FILTER (WHERE library = 'jackson') AS jackson,
       COUNT(*) FILTER (WHERE library = 'kotlinx') AS kotlinx
FROM binned
GROUP BY bucket
ORDER BY bucket
"""


def percentile_sql():
    return f"""
WITH selected AS (
  SELECT DISTINCT trim(both FROM x) AS p
  FROM unnest(string_to_array('${{percentile:csv}}', ',')) AS t(x)
  WHERE trim(both FROM x) <> ''
)
SELECT t.model || '·' || t.direction
         || CASE WHEN (SELECT count(*) FROM selected) > 1 THEN '·' || t.p ELSE '' END AS scenario,
       MAX(t.pct) FILTER (WHERE t.library = 'jackson') AS jackson,
       MAX(t.pct) FILTER (WHERE t.library = 'kotlinx') AS kotlinx
FROM (
  SELECT c.model, c.direction, c.library, s.p,
         CASE s.p
           WHEN 'p50' THEN c.p50_ns
           WHEN 'p75' THEN c.p75_ns
           WHEN 'p90' THEN c.p90_ns
           WHEN 'p95' THEN c.p95_ns
           WHEN 'p99' THEN c.p99_ns
         END AS pct
  FROM benchmark_cases c
  CROSS JOIN selected s
  WHERE c.run_id = {LATEST_RUN}
) t
GROUP BY t.model, t.direction, t.p
ORDER BY t.model,
         CASE t.direction WHEN 'read' THEN 1 WHEN 'write' THEN 2 ELSE 3 END,
         CASE t.p WHEN 'p50' THEN 1 WHEN 'p75' THEN 2 WHEN 'p90' THEN 3 WHEN 'p95' THEN 4 ELSE 5 END
"""


def custom_var(name, label, options, current_value, multi=False):
    currents = current_value if isinstance(current_value, list) else [current_value]
    opts = []
    current_texts = []
    for value, text in options:
        selected = value in currents
        opts.append({"text": text, "value": value, "selected": selected})
        if selected:
            current_texts.append(text)
    query = ",".join(f"{value} : {text}" for value, text in options)
    if multi:
        current = {"selected": True, "text": current_texts, "value": currents}
    else:
        current = {"selected": True, "text": current_texts[0], "value": currents[0]}
    return {
        "type": "custom",
        "name": name,
        "label": label,
        "hide": 0,
        "includeAll": False,
        "multi": multi,
        "skipUrlSync": False,
        "query": query,
        "options": opts,
        "current": current,
    }


def build_panels():
    panels = []
    pid = 1
    y = 0

    panels.append({
        "id": pid,
        "type": "text",
        "title": "",
        "gridPos": {"h": 8, "w": 24, "x": 0, "y": y},
        "transparent": True,
        "options": {"mode": "markdown", "content": TEXT_CONTENT},
    })
    pid += 1
    y += 8

    stats = [
        ("Overhead single, нс/оп",
         f"SELECT generated_at AS time, overhead_single_ns AS value FROM benchmark_runs WHERE run_id = {LATEST_RUN}"),
        ("Overhead concurrent, нс/оп",
         f"SELECT generated_at AS time, overhead_concurrent_ns AS value FROM benchmark_runs WHERE run_id = {LATEST_RUN}"),
        ("Прогонов в БД",
         "SELECT now() AS time, (SELECT count(*) FROM benchmark_runs)::double precision AS value"),
        ("Сэмплов в последнем прогоне",
         f"SELECT now() AS time, (SELECT count(*) FROM benchmark_samples WHERE run_id = {LATEST_RUN})::double precision AS value"),
    ]
    for i, (title, sql) in enumerate(stats):
        panels.append(panel(
            pid, "stat", title,
            {"h": 4, "w": 6, "x": i * 6, "y": y},
            target(sql),
            field_config={"defaults": {"decimals": 2, "color": {"mode": "thresholds"}}, "overrides": []},
            options={
                "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                "orientation": "auto",
                "textMode": "auto",
                "colorMode": "value",
                "graphMode": "none",
                "justifyMode": "auto",
            },
        ))
        pid += 1
    y += 4

    table_opts = {"showHeader": True, "cellHeight": "sm", "footer": {"show": False, "countRows": False, "reducer": ["sum"]}}
    panels.append(panel(
        pid, "table", "Окружение прогона",
        {"h": 8, "w": 12, "x": 0, "y": y},
        target(f"SELECT key, value FROM benchmark_runs, jsonb_each_text(environment) "
               f"WHERE run_id = {LATEST_RUN} ORDER BY key"),
        options=table_opts,
    ))
    pid += 1
    panels.append(panel(
        pid, "table", "Конфигурация прогона",
        {"h": 8, "w": 12, "x": 12, "y": y},
        target(f"SELECT key, value FROM benchmark_runs, jsonb_each_text(config) "
               f"WHERE run_id = {LATEST_RUN} ORDER BY key"),
        options=table_opts,
    ))
    pid += 1
    y += 8

    panels.append(panel(
        pid, "table",
        "Сводное сравнение: модель × сценарий (Δ = (kotlinx − Jackson)/Jackson × 100%)",
        {"h": 8, "w": 24, "x": 0, "y": y},
        target(
            f"SELECT model, direction, median_jackson_ns, median_kotlinx_ns, "
            f"delta_median_pct, delta_mean_pct, delta_p99_pct, faster_library "
            f"FROM benchmark_comparisons WHERE run_id = {LATEST_RUN} "
            f"ORDER BY model, direction"
        ),
        options=table_opts,
    ))
    pid += 1
    y += 8

    panels.append(row(pid, "Перцентили: Jackson vs kotlinx (переключатель percentile)", y))
    pid += 1
    y += 1

    panels.append(panel(
        pid, "barchart",
        "${percentile}: ns/операция — Jackson 3 vs kotlinx.serialization (3 модели × 3 сценария)",
        {"h": 10, "w": 24, "x": 0, "y": y},
        target(percentile_sql()),
        field_config(
            {
                "custom": {
                    "fillOpacity": 85,
                    "gradientMode": "none",
                    "lineWidth": 1,
                    "axisLabel": "нс/операция",
                },
                "decimals": 1,
            },
            library_overrides(),
        ),
        options={
            "xField": "scenario",
            "orientation": "vertical",
            "showValue": "auto",
            "stacking": "none",
            "barWidth": 0.7,
            "groupWidth": 0.7,
            "xTickLabelRotation": 45,
            "xTickLabelMaxLength": 40,
            "legend": legend(),
            "tooltip": tooltip_multi(),
        },
    ))
    pid += 1
    y += 10

    trend_fc = field_config(
        {
            "custom": {
                "drawStyle": "line",
                "lineInterpolation": "linear",
                "barAlignment": 0,
                "lineWidth": 1,
                "fillOpacity": 10,
                "gradientMode": "none",
                "spanNulls": True,
                "showPoints": "never",
                "pointSize": 3,
                "stacking": {"mode": "none", "group": "A"},
                "axisPlacement": "auto",
                "axisLabel": "латентность, нс/оп",
                "axisColorMode": "text",
                "scaleDistribution": {"type": "linear"},
                "hideFrom": {"legend": False, "tooltip": False, "viz": False},
            },
            "decimals": 1,
        },
        library_overrides() + percentile_line_overrides(),
    )
    trend_opts = {
        "legend": legend(),
        "tooltip": tooltip_multi(),
        "xField": "sample",
    }

    panels.append(row(pid, "Временной ряд — ось X: номер сэмпла, ось Y: нс/оп; асимптоты p50/p95 (+ выбранные)", y))
    pid += 1
    y += 1
    for i, d in enumerate(DIRECTIONS):
        panels.append(panel(
            pid, "trend",
            f"{d}: Jackson vs kotlinx — временной ряд",
            {"h": 10, "w": 8, "x": i * 8, "y": y},
            target(timeseries_sql(d)),
            trend_fc,
            trend_opts,
            extra={"transformations": percentile_field_filter("sample")},
        ))
        pid += 1
    y += 10

    # min/max 0–1 только у Y (доля). Если задать в defaults, Grafana trend
    # применяет их и к xField latency_ns — кривая уезжает за пределы оси X.
    y_fraction = [{"id": "min", "value": 0}, {"id": "max", "value": 1}]
    cdf_fc = field_config(
        {
            "custom": {
                "drawStyle": "line",
                "lineInterpolation": "linear",
                "barAlignment": 0,
                "lineWidth": 1,
                "fillOpacity": 10,
                "gradientMode": "none",
                "spanNulls": True,
                "showPoints": "never",
                "pointSize": 3,
                "stacking": {"mode": "none", "group": "A"},
                "axisPlacement": "auto",
                "axisLabel": "доля запросов",
                "axisColorMode": "text",
                "scaleDistribution": {"type": "linear"},
                "hideFrom": {"legend": False, "tooltip": False, "viz": False},
            },
            "decimals": 3,
        },
        library_overrides(extra_jackson=y_fraction, extra_kotlinx=y_fraction)
        + percentile_line_overrides(y_fraction)
        + [{
            "matcher": {"id": "byName", "options": "latency_ns"},
            "properties": [
                {"id": "displayName", "value": "латентность, нс/оп"},
                {"id": "decimals", "value": 1},
                {"id": "custom.axisLabel", "value": "латентность, нс/оп"},
                {"id": "custom.hideFrom", "value": {
                    "legend": True, "tooltip": True, "viz": False,
                }},
            ],
        }],
    )
    cdf_opts = {
        "legend": legend(),
        "tooltip": tooltip_multi(),
        "xField": "latency_ns",
    }

    panels.append(row(pid, "CDF — ось X: латентность нс/оп, ось Y: доля запросов; вертикали p50/p95 (+ выбранные)", y))
    pid += 1
    y += 1
    for i, d in enumerate(DIRECTIONS):
        panels.append(panel(
            pid, "trend",
            f"{d}: Jackson vs kotlinx — CDF",
            {"h": 10, "w": 8, "x": i * 8, "y": y},
            target(cdf_sql(d)),
            cdf_fc,
            cdf_opts,
            extra={"transformations": percentile_field_filter("latency_ns")},
        ))
        pid += 1
    y += 10

    hist_fc = field_config(
        {
            "custom": {
                "fillOpacity": 80,
                "gradientMode": "none",
                "lineWidth": 1,
                "axisLabel": "число сэмплов",
            },
            "decimals": 0,
        },
        library_overrides(),
    )
    panels.append(row(pid, "Гистограмма — общие бины, столбцы Jackson и kotlinx рядом (как SVG overlay)", y))
    pid += 1
    y += 1
    for i, d in enumerate(DIRECTIONS):
        panels.append(panel(
            pid, "barchart",
            f"{d}: Jackson vs kotlinx — гистограмма",
            {"h": 10, "w": 8, "x": i * 8, "y": y},
            target(hist_sql(d)),
            hist_fc,
            {
                "xField": "latency_ns",
                "orientation": "vertical",
                "showValue": "never",
                "stacking": "none",
                "barWidth": 0.9,
                "groupWidth": 0.8,
                "xTickLabelRotation": 45,
                "xTickLabelMaxLength": 12,
                "legend": legend(),
                "tooltip": tooltip_multi(),
            },
        ))
        pid += 1

    return panels


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "docker", "grafana", "provisioning", "dashboards", "benchmark.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)

    dashboard = {
        "id": None,
        "uid": "benchmark-lr3",
        "title": "ЛР3: Jackson 3 vs kotlinx.serialization — микробенчмарк (x1/x4/x8)",
        "tags": ["lr3", "benchmark"],
        "timezone": "browser",
        "editable": True,
        "graphTooltip": 1,
        "schemaVersion": 39,
        "refresh": "",
        "time": {"from": "now-6h", "to": "now"},
        "timepicker": {"hidden": True},
        "links": [
            {
                "title": "ЛР4: auth (матрикс)",
                "url": "/d/loadtest-lr4-auth",
                "type": "link",
                "icon": "external link",
                "targetBlank": False,
                "keepTime": True,
                "includeVars": False,
                "tooltip": "Перейти к нагрузочным прогонам ЛР4 (auth)",
            },
            {
                "title": "ЛР4: public",
                "url": "/d/loadtest-lr4-public",
                "type": "link",
                "icon": "external link",
                "targetBlank": False,
                "keepTime": True,
                "includeVars": False,
                "tooltip": "Перейти к нагрузочным прогонам ЛР4 (public)",
            },
        ],
        "templating": {
            "list": [
                custom_var(
                    "model",
                    "Модель",
                    [("x1", "x1"), ("x4", "x4"), ("x8", "x8")],
                    "x1",
                ),
                custom_var(
                    "percentile",
                    "Перцентиль",
                    [
                        ("p50", "p50 (медиана)"),
                        ("p75", "p75"),
                        ("p90", "p90"),
                        ("p95", "p95"),
                        ("p99", "p99"),
                    ],
                    ["p50", "p95"],
                    multi=True,
                ),
            ]
        },
        "annotations": {"list": []},
        "panels": build_panels(),
    }

    payload = {
        "dashboard": dashboard,
        "overwrite": True,
        "inputs": [],
        "folderId": 0,
    }

    with open(out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"OK: {out} ({len(dashboard['panels'])} панелей)")


if __name__ == "__main__":
    main()
