#!/usr/bin/env python3
"""Импорт report.json микробенчмарка ЛР3 в PostgreSQL (для Grafana).

Генерирует SQL (INSERT ... ON CONFLICT DO UPDATE) для таблиц
benchmark_runs / benchmark_cases / benchmark_samples / benchmark_cdf /
benchmark_histogram / benchmark_percentiles / benchmark_comparisons.

Использование:
  python3 scripts/import-benchmark-to-db.py <report.json> [--run-id ID] [--sql out.sql]
  (без --sql SQL печатается в stdout — удобно для пайпа в psql)

Обёртка для docker: scripts/import-benchmark-to-db.sh
"""
import argparse
import json
import sys

TABLES = [
    "benchmark_comparisons",
    "benchmark_percentiles",
    "benchmark_histogram",
    "benchmark_cdf",
    "benchmark_samples",
    "benchmark_cases",
    "benchmark_runs",
]


def esc(v):
    """Экранирование строкового литерала для SQL."""
    if v is None:
        return "NULL"
    return "'" + str(v).replace("'", "''") + "'"


def num(v):
    if v is None:
        return "NULL"
    return repr(float(v))


def int_(v):
    if v is None:
        return "NULL"
    return str(int(v))


def multi_insert(table, columns, rows, chunk=500):
    """INSERT многострочными VALUES (пачками), ON CONFLICT DO NOTHING."""
    col_sql = ", ".join(columns)
    stmts = []
    for i in range(0, len(rows), chunk):
        values = ",\n".join("(" + ", ".join(row) + ")" for row in rows[i:i + chunk])
        stmts.append(
            f"INSERT INTO {table} ({col_sql}) VALUES\n{values}\nON CONFLICT DO NOTHING;"
        )
    return stmts


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("report", help="путь к report.json")
    ap.add_argument("--run-id", default=None, help="идентификатор прогона (по умолчанию — из каталога отчёта)")
    ap.add_argument("--sql", default=None, help="файл для SQL (по умолчанию stdout)")
    args = ap.parse_args()

    with open(args.report, encoding="utf-8") as f:
        doc = json.load(f)

    run_id = args.run_id or "run"

    lines = ["-- Импорт прогона %s (%s)" % (run_id, doc.get("generatedAt", "")), "BEGIN;"]

    # ---- удаляем предыдущие данные этого прогона (переимпорт идемпотентен) ----
    for t in TABLES:
        lines.append(f"DELETE FROM {t} WHERE run_id = {esc(run_id)};")

    # ---- benchmark_runs ----
    env = json.dumps(doc.get("environment", {}), ensure_ascii=False)
    cfg = json.dumps(doc.get("config", {}), ensure_ascii=False)
    overhead = doc.get("overhead", {})
    lines.append(
        "INSERT INTO benchmark_runs "
        "(run_id, title, generated_at, environment, config, overhead_single_ns, overhead_concurrent_ns) VALUES "
        f"({esc(run_id)}, {esc(doc.get('title', ''))}, {esc(doc.get('generatedAt', ''))}, "
        f"{esc(env)}::jsonb, {esc(cfg)}::jsonb, "
        f"{num(overhead.get('singleNsPerOp'))}, {num(overhead.get('concurrentNsPerOp'))}) "
        "ON CONFLICT (run_id) DO UPDATE SET title = EXCLUDED.title, generated_at = EXCLUDED.generated_at, "
        "environment = EXCLUDED.environment, config = EXCLUDED.config, "
        "overhead_single_ns = EXCLUDED.overhead_single_ns, "
        "overhead_concurrent_ns = EXCLUDED.overhead_concurrent_ns;"
    )

    # ---- benchmark_cases ----
    case_rows = []
    for c in doc.get("cases", []):
        s = c.get("stats", {})
        case_rows.append((
            esc(run_id), esc(c.get("model", "x1")), esc(c.get("name", "")), esc(c.get("description", "")),
            esc(c.get("scenario", "")), esc(c.get("direction", "")), esc(c.get("library", "")),
            int_(c.get("opsPerSample")), int_(c.get("totalOps")),
            num(c.get("throughputOpsPerSec")), num(c.get("netMedianNs")),
            num(s.get("meanNs")), num(s.get("stdevNs")), num(s.get("minNs")), num(s.get("maxNs")),
            num(s.get("p50Ns")), num(s.get("p75Ns")), num(s.get("p90Ns")), num(s.get("p95Ns")), num(s.get("p99Ns")),
            num(c.get("measure", {}).get("durationMs")),
            int_(c.get("warmup", {}).get("ops")), int_(c.get("settle", {}).get("ops")),
            int_(c.get("measure", {}).get("ops")), int_(c.get("teardown", {}).get("ops")),
        ))
    lines.extend(multi_insert(
        "benchmark_cases",
        ["run_id", "model", "name", "description", "scenario", "direction", "library",
         "ops_per_sample", "total_ops", "throughput_ops_per_sec", "net_median_ns",
         "mean_ns", "stdev_ns", "min_ns", "max_ns", "p50_ns", "p75_ns", "p90_ns", "p95_ns", "p99_ns",
         "measure_duration_ms", "warmup_ops", "settle_ops", "measure_ops", "teardown_ops"],
        case_rows,
    ))

    # ---- benchmark_samples / cdf / histogram / percentiles ----
    sample_rows, cdf_rows, hist_rows, pct_rows = [], [], [], []
    for c in doc.get("cases", []):
        name = c.get("name", "")
        model = c.get("model", "x1")
        library = c.get("library", "")
        direction = c.get("direction", "")
        s = c.get("stats", {})
        for i, v in enumerate(s.get("timeSeriesNs", [])):
            sample_rows.append((esc(run_id), esc(name), esc(model), esc(library), esc(direction),
                                int_(i), num(v)))
        for p in s.get("cdf", []):
            cdf_rows.append((esc(run_id), esc(name), esc(model), esc(library), esc(direction),
                             num(p.get("latencyNs")), num(p.get("fraction"))))
        for i, b in enumerate(s.get("histogram", [])):
            hist_rows.append((esc(run_id), esc(name), esc(model), esc(library), esc(direction),
                              int_(i), num(b.get("fromNs")), num(b.get("toNs")), int_(b.get("count"))))
        for p in s.get("percentiles", []):
            pct_rows.append((esc(run_id), esc(name), esc(model), esc(library), esc(direction),
                             num(p.get("percentile")), num(p.get("ns"))))

    lines.extend(multi_insert(
        "benchmark_samples",
        ["run_id", "case_name", "model", "library", "direction", "sample_index", "ns_per_op"],
        sample_rows,
    ))
    lines.extend(multi_insert(
        "benchmark_cdf",
        ["run_id", "case_name", "model", "library", "direction", "latency_ns", "fraction"],
        cdf_rows,
    ))
    lines.extend(multi_insert(
        "benchmark_histogram",
        ["run_id", "case_name", "model", "library", "direction", "bucket_index", "from_ns", "to_ns", "count"],
        hist_rows,
    ))
    lines.extend(multi_insert(
        "benchmark_percentiles",
        ["run_id", "case_name", "model", "library", "direction", "percentile", "ns"],
        pct_rows,
    ))

    # ---- benchmark_comparisons ----
    cmp_rows = []
    for cmp_ in doc.get("comparisons", []):
        cmp_rows.append((
            esc(run_id), esc(cmp_.get("model", "x1")), esc(cmp_.get("direction", "")),
            esc(cmp_.get("scenario", "")),
            num(cmp_.get("medianANs")), num(cmp_.get("medianBNs")),
            num(cmp_.get("deltaMedianPct")), num(cmp_.get("deltaMeanPct")), num(cmp_.get("deltaP99Pct")),
            esc(cmp_.get("fasterLibrary", "")),
        ))
    lines.extend(multi_insert(
        "benchmark_comparisons",
        ["run_id", "model", "direction", "scenario", "median_jackson_ns", "median_kotlinx_ns",
         "delta_median_pct", "delta_mean_pct", "delta_p99_pct", "faster_library"],
        cmp_rows,
    ))

    lines.append("COMMIT;")

    sql = "\n".join(lines) + "\n"
    if args.sql:
        with open(args.sql, "w", encoding="utf-8") as f:
            f.write(sql)
        print(f"OK: {args.sql} ({len(sample_rows)} сэмплов, {len(cdf_rows)} CDF, "
              f"{len(hist_rows)} гистограмма, {len(pct_rows)} перцентилей, "
              f"{len(case_rows)} кейсов, {len(cmp_rows)} сравнений)")
    else:
        sys.stdout.write(sql)


if __name__ == "__main__":
    main()