#!/usr/bin/env python3
"""ЛР4 — перенос CDF и гистограммы на ось времени.

Grafana-панели распределений должны подчиняться таймпикеру, поэтому из
`loadtest/results/<run>/raw.json` строятся 5-секундные окна:

  • `lt_cdf_ts`       — доля запросов ≤ порога (10/25/50/100/200/500/1000/2000/5000/10000 мс);
  • `lt_histogram_ts` — 40 бинов по 250 мс (хвост > 10 с попадает в последний бин).

Скрипт не запускает нагрузочные тесты. Он только перечитывает уже сохранённые
`raw.json` и пишет строки в PostgreSQL Grafana. Большие старые raw.json можно
пропустить через `--max-size-mb` (по умолчанию 100 MiB).

Использование:
  python3 scripts/backfill-loadtest-distributions.py
  python3 scripts/backfill-loadtest-distributions.py --only lr4-v2-smoke-auth
  python3 scripts/backfill-loadtest-distributions.py --max-size-mb 2048
  python3 scripts/backfill-loadtest-distributions.py --sql /tmp/distributions.sql --no-apply
"""
import argparse
import bisect
import json
import math
import os
import subprocess
from collections import defaultdict
from datetime import datetime, timezone

THRESHOLDS_MS = [10, 25, 50, 100, 200, 500, 1000, 2000, 5000, 10000]
HIST_BIN_MS = 250.0
HIST_BINS = 40

DDL = """
CREATE TABLE IF NOT EXISTS lt_cdf_ts (
    run_id      TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    phase       TEXT NOT NULL,
    operation   TEXT NOT NULL DEFAULT 'all',
    bucket_ts   TIMESTAMPTZ NOT NULL,
    latency_ms  DOUBLE PRECISION NOT NULL,
    fraction    DOUBLE PRECISION NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lt_cdf_ts_lookup
    ON lt_cdf_ts(run_id, phase, operation, bucket_ts);
CREATE TABLE IF NOT EXISTS lt_histogram_ts (
    run_id       TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    phase        TEXT NOT NULL,
    operation    TEXT NOT NULL DEFAULT 'all',
    bucket_ts    TIMESTAMPTZ NOT NULL,
    bucket_index INT NOT NULL,
    from_ms      DOUBLE PRECISION NOT NULL,
    to_ms        DOUBLE PRECISION NOT NULL,
    count        BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lt_hist_ts_lookup
    ON lt_histogram_ts(run_id, phase, operation, bucket_ts);
"""


def q(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def parse_time(value):
    if not value:
        return None
    text = str(value).replace("Z", "+00:00")
    try:
        return datetime.fromisoformat(text).astimezone(timezone.utc).timestamp()
    except ValueError:
        return None


def iso(epoch):
    return datetime.fromtimestamp(epoch, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def duration_points(path):
    """Потоково читает JSONL k6 и отдаёт (epoch, ms, phase, operation)."""
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if '"http_req_duration"' not in line:
                continue
            try:
                obj = json.loads(line)
            except ValueError:
                continue
            if obj.get("type") != "Point" or obj.get("metric") != "http_req_duration":
                continue
            data = obj.get("data") or {}
            ts = parse_time(data.get("time"))
            if ts is None:
                continue
            try:
                value = float(data.get("value") or 0.0)
            except (TypeError, ValueError):
                continue
            tags = data.get("tags") or {}
            phase = str(tags.get("phase") or "default")
            operation = str(tags.get("operation") or "unknown")
            yield ts, value, phase, operation


def build_rows(path, bucket_seconds):
    buckets = defaultdict(list)
    for ts, value, phase, operation in duration_points(path):
        bucket = int(math.floor(ts / bucket_seconds)) * bucket_seconds
        buckets[(phase, "all", bucket)].append(value)
        buckets[(phase, operation, bucket)].append(value)

    cdf_rows = []
    hist_rows = []
    for (phase, operation, bucket), values in buckets.items():
        ordered = sorted(values)
        total = len(ordered)
        if total == 0:
            continue
        bucket_ts = iso(bucket)
        for threshold in THRESHOLDS_MS:
            fraction = bisect.bisect_right(ordered, threshold) / total
            cdf_rows.append((phase, operation, bucket_ts, threshold, round(fraction, 6)))
        counts = [0] * HIST_BINS
        for value in ordered:
            idx = min(HIST_BINS - 1, max(0, int(value / HIST_BIN_MS)))
            counts[idx] += 1
        for idx, count in enumerate(counts):
            if count == 0:
                continue
            hist_rows.append((
                phase, operation, bucket_ts, idx,
                idx * HIST_BIN_MS, (idx + 1) * HIST_BIN_MS, count,
            ))
    return cdf_rows, hist_rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--results-dir", default="loadtest/results")
    parser.add_argument("--bucket-seconds", type=int, default=5)
    parser.add_argument("--max-size-mb", type=float, default=100.0,
                        help="Пропускать raw.json больше этого размера (0 = без лимита)")
    parser.add_argument("--only", default=None, help="Только указанный run_id")
    parser.add_argument("--sql", default="/tmp/loadtest_distributions.sql")
    parser.add_argument("--no-apply", action="store_true")
    args = parser.parse_args()

    lines = ["BEGIN;", DDL]
    processed = []
    skipped = []
    for name in sorted(os.listdir(args.results_dir)):
        run_dir = os.path.join(args.results_dir, name)
        raw = os.path.join(run_dir, "raw.json")
        if not os.path.isfile(raw):
            continue
        if name == "latest" or (args.only and name != args.only):
            continue
        size_mb = os.path.getsize(raw) / 1024 / 1024
        if args.max_size_mb and size_mb > args.max_size_mb:
            skipped.append((name, size_mb))
            continue

        cdf_rows, hist_rows = build_rows(raw, args.bucket_seconds)
        if not cdf_rows:
            continue
        lines.append(f"DELETE FROM lt_cdf_ts WHERE run_id = {q(name)};")
        lines.append(f"DELETE FROM lt_histogram_ts WHERE run_id = {q(name)};")
        for phase, operation, bucket_ts, latency, fraction in cdf_rows:
            lines.append(
                "INSERT INTO lt_cdf_ts (run_id, phase, operation, bucket_ts, latency_ms, fraction) "
                f"VALUES ({q(name)}, {q(phase)}, {q(operation)}, {q(bucket_ts)}, "
                f"{latency}, {fraction});"
            )
        for phase, operation, bucket_ts, idx, from_ms, to_ms, count in hist_rows:
            lines.append(
                "INSERT INTO lt_histogram_ts (run_id, phase, operation, bucket_ts, bucket_index, "
                "from_ms, to_ms, count) "
                f"VALUES ({q(name)}, {q(phase)}, {q(operation)}, {q(bucket_ts)}, {idx}, "
                f"{from_ms}, {to_ms}, {count});"
            )
        processed.append((name, len(cdf_rows), len(hist_rows)))
        print(f"[dist] {name}: cdf={len(cdf_rows)}, hist={len(hist_rows)}")

    lines.append("COMMIT;")
    with open(args.sql, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    print(f"[dist] SQL: {args.sql} ({os.path.getsize(args.sql) / 1024 / 1024:.1f} MiB)")
    for name, size in skipped:
        print(f"[dist] пропущен {name}: raw.json {size:.1f} MiB > {args.max_size_mb} MiB")

    if args.no_apply:
        return
    result = subprocess.run(
        ["docker", "exec", "-i", "viday_bench_postgres",
         "psql", "-U", "benchmark_user", "-d", "benchmark", "-v", "ON_ERROR_STOP=1"],
        input=open(args.sql, "r", encoding="utf-8").read(),
        text=True,
    )
    if result.returncode != 0:
        raise SystemExit(f"psql завершился с кодом {result.returncode}")
    print(f"[dist] применено прогонов: {len(processed)}")


if __name__ == "__main__":
    main()
