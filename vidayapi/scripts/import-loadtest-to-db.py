#!/usr/bin/env python3
"""ЛР4 — импорт report.json нагрузочного прогона в PostgreSQL для Grafana.

Использование:
  python3 scripts/import-loadtest-to-db.py loadtest/results/<run-id>/report.json \
      --run-id <run-id> --sql /tmp/loadtest_import.sql

Скрипт идемпотентен: сначала применяет схему (CREATE TABLE IF NOT EXISTS),
затем удаляет старые строки этого run_id (каскадно) и вставляет новые.
"""
import argparse
import json
import os
import sys


def q(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)):
        return str(value)
    text = str(value).replace("'", "''")
    return f"'{text}'"


def jq(value):
    return q(json.dumps(value, ensure_ascii=False))


def to_float(value, default=0.0):
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def to_int(value, default=0):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("report")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--sql", default="/tmp/loadtest_import.sql")
    parser.add_argument("--schema", default="docker/grafana/init/02_loadtest_schema.sql")
    parser.add_argument("--report-path", default=None)
    args = parser.parse_args()

    with open(args.report, "r", encoding="utf-8") as fh:
        report = json.load(fh)

    lines = ["BEGIN;"]
    if os.path.exists(args.schema):
        with open(args.schema, "r", encoding="utf-8") as fh:
            lines.append(fh.read())
    lines.append(f"DELETE FROM lt_runs WHERE run_id = {q(args.run_id)};")

    config = report.get("config") or {}
    run = {
        "run_id": args.run_id,
        "title": report.get("title") or f"LR4 {report.get('profile', '')}",
        "profile": report.get("profile") or "",
        "scenario": report.get("scenario") or report.get("profile") or "",
        "phase": (report.get("phases") or [{}])[0].get("phase") if report.get("phases") else "",
        "started_at": report.get("started_at"),
        "finished_at": report.get("finished_at"),
        "duration_s": to_float(report.get("duration_s")),
        "status": "early_stopped" if report.get("early_stopped") else "completed",
        "error_rate_target": to_float(config.get("error_rate", 0.0)),
        "config": config,
        "environment": report.get("environment") or {},
        "summary": {
            "findings": report.get("findings") or [],
            "ramp_curve": report.get("ramp_curve") or [],
            "recovery": report.get("recovery"),
            "resources": (report.get("resources") or {}).get("components") or {},
        },
        "report_path": args.report_path or args.report,
    }
    lines.append(
        "INSERT INTO lt_runs (run_id, title, profile, scenario, phase, started_at, finished_at, "
        "duration_s, status, error_rate_target, config, environment, summary, report_path) VALUES ("
        f"{q(run['run_id'])}, {q(run['title'])}, {q(run['profile'])}, {q(run['scenario'])}, "
        f"{q(run['phase'])}, {q(run['started_at'])}, {q(run['finished_at'])}, "
        f"{run['duration_s']}, {q(run['status'])}, {run['error_rate_target']}, "
        f"{jq(run['config'])}, {jq(run['environment'])}, {jq(run['summary'])}, {q(run['report_path'])});"
    )

    for phase in report.get("phases") or []:
        insert_summary(lines, args.run_id, phase, "overall", "all")
    for operation in report.get("operations") or []:
        insert_summary(lines, args.run_id, operation, "operation", operation.get("operation") or "unknown")

    percentiles = report.get("percentiles") or [0.5, 0.75, 0.9, 0.95, 0.99, 0.997]
    for phase in report.get("phases") or []:
        for p in percentiles:
            lines.append(
                "INSERT INTO lt_percentiles (run_id, scenario, phase, scope, operation, percentile, latency_ms) VALUES ("
                f"{q(args.run_id)}, {q(phase.get('scenario'))}, {q(phase.get('phase'))}, 'overall', 'all', "
                f"{to_float(p)}, {to_float(phase.get(percentile_field(p)))});"
            )
    for operation in report.get("operations") or []:
        for p in percentiles:
            lines.append(
                "INSERT INTO lt_percentiles (run_id, scenario, phase, scope, operation, percentile, latency_ms) VALUES ("
                f"{q(args.run_id)}, {q(operation.get('scenario'))}, {q(operation.get('phase'))}, 'operation', "
                f"{q(operation.get('operation'))}, {to_float(p)}, {to_float(operation.get(percentile_field(p)))});"
            )

    for row in report.get("cdf") or []:
        lines.append(
            "INSERT INTO lt_cdf (run_id, scenario, phase, scope, operation, fraction, latency_ms) VALUES ("
            f"{q(args.run_id)}, {q(row.get('scenario'))}, {q(row.get('phase'))}, {q(row.get('scope'))}, "
            f"{q(row.get('operation'))}, {to_float(row.get('fraction'))}, {to_float(row.get('latency_ms'))});"
        )
    for row in report.get("histogram") or []:
        lines.append(
            "INSERT INTO lt_histogram (run_id, scenario, phase, scope, operation, bucket_index, from_ms, to_ms, count) VALUES ("
            f"{q(args.run_id)}, {q(row.get('scenario'))}, {q(row.get('phase'))}, {q(row.get('scope'))}, "
            f"{q(row.get('operation'))}, {to_int(row.get('bucket_index'))}, {to_float(row.get('from_ms'))}, "
            f"{to_float(row.get('to_ms'))}, {to_int(row.get('count'))});"
        )
    for row in report.get("timeseries") or []:
        lines.append(
            "INSERT INTO lt_timeseries (run_id, scenario, phase, operation, bucket_ts, requests, rps, vus, "
            "errors_unexpected, errors_business, errors_injected, mean_ms, p50_ms, p75_ms, p90_ms, p95_ms, p99_ms, p997_ms, max_ms) VALUES ("
            f"{q(args.run_id)}, {q(row.get('scenario'))}, {q(row.get('phase'))}, {q(row.get('operation') or 'all')}, {q(row.get('bucket_ts'))}, "
            f"{to_int(row.get('requests'))}, {to_float(row.get('rps'))}, {to_float(row.get('vus'))}, "
            f"{to_int(row.get('errors_unexpected'))}, {to_int(row.get('errors_business'))}, "
            f"{to_int(row.get('errors_injected'))}, {to_float(row.get('mean_ms'))}, "
            f"{to_float(row.get('p50_ms'))}, {to_float(row.get('p75_ms'))}, {to_float(row.get('p90_ms'))}, "
            f"{to_float(row.get('p95_ms'))}, {to_float(row.get('p99_ms'))}, {to_float(row.get('p997_ms'))}, "
            f"{to_float(row.get('max_ms'))});"
        )

    resources = list(report.get("resource_phases") or []) + list(
        (report.get("resources") or {}).get("all") or []
    )
    for row in resources:
        insert_resource(lines, args.run_id, row)
    for row in report.get("resource_samples") or []:
        lines.append(
            "INSERT INTO lt_resource_samples (run_id, ts, component, cpu_pct, ram_bytes, disk_read_bps, "
            "disk_write_bps, net_rx_bps, net_tx_bps, pids) VALUES ("
            f"{q(args.run_id)}, {q(row.get('ts'))}, {q(row.get('component'))}, {to_float(row.get('cpu_pct'))}, "
            f"{to_float(row.get('ram_bytes'))}, {to_float(row.get('disk_read_bps'))}, "
            f"{to_float(row.get('disk_write_bps'))}, {to_float(row.get('net_rx_bps'))}, "
            f"{to_float(row.get('net_tx_bps'))}, {to_int(row.get('pids'))});"
        )

    app_fields = [
        "threads_live", "threads_daemon", "threads_runnable", "threads_waiting",
        "requests_in_flight", "executor_active", "executor_pool", "heap_bytes",
        "gc_pause_rate", "hikari_active", "hikari_idle", "http_rps", "http_5xx_rps",
        "tomcat_active", "tomcat_rejected_rate",
    ]
    for row in report.get("app_samples") or []:
        values = [q(args.run_id), q(row.get("ts"))]
        values.extend(to_float(row.get(field)) for field in app_fields)
        lines.append(
            f"INSERT INTO lt_app_samples (run_id, ts, {', '.join(app_fields)}) "
            f"VALUES ({', '.join(map(str, values))});"
        )

    for row in report.get("ramp_curve") or []:
        lines.append(
            "INSERT INTO lt_ramp_curve (run_id, target_vus, requests, rps, errors_unexpected, errors_business, "
            "unexpected_error_rate_pct, mean_ms, p95_ms, p99_ms, stable) VALUES ("
            f"{q(args.run_id)}, {to_int(row.get('target_vus'))}, {to_int(row.get('requests'))}, "
            f"{to_float(row.get('rps'))}, {to_int(row.get('errors_unexpected'))}, "
            f"{to_int(row.get('errors_business'))}, {to_float(row.get('unexpected_error_rate_pct'))}, "
            f"{to_float(row.get('mean_ms'))}, {to_float(row.get('p95_ms'))}, {to_float(row.get('p99_ms'))}, "
            f"{q(bool(row.get('stable')))});"
        )

    recovery = report.get("recovery") or {}
    recovery_s = recovery.get("recovery_seconds")
    for phase in report.get("phases") or []:
        note = ""
        if phase.get("phase") == "recovery":
            note = f"recovery_s={recovery_s}"
        lines.append(
            "INSERT INTO lt_phases (run_id, scenario, phase, started_at, finished_at, requests, rps, "
            "unexpected_error_rate_pct, p50_ms, p95_ms, p99_ms, recovery_s, note) VALUES ("
            f"{q(args.run_id)}, {q(phase.get('scenario'))}, {q(phase.get('phase'))}, "
            f"{q(phase.get('started_at'))}, {q(phase.get('finished_at'))}, {to_int(phase.get('requests'))}, "
            f"{to_float(phase.get('rps'))}, {to_float(phase.get('unexpected_error_rate_pct'))}, "
            f"{to_float(phase.get('p50_ms'))}, {to_float(phase.get('p95_ms'))}, {to_float(phase.get('p99_ms'))}, "
            f"{q(recovery_s) if phase.get('phase') == 'recovery' and recovery_s is not None else 'NULL'}, {q(note)});"
        )

    for finding in report.get("findings") or []:
        lines.append(
            "INSERT INTO lt_findings (run_id, kind, key, value, detail) VALUES ("
            f"{q(args.run_id)}, {q(finding.get('kind'))}, {q(finding.get('key'))}, "
            f"{q(finding.get('value'))}, {q(finding.get('detail'))});"
        )

    lines.append("COMMIT;")
    with open(args.sql, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    print(f"[import] SQL: {args.sql} ({os.path.getsize(args.sql) / 1024 / 1024:.1f} MiB)")


def percentile_field(p):
    p = float(p)
    if abs(p - 0.5) < 1e-9:
        return "p50_ms"
    if abs(p - 0.75) < 1e-9:
        return "p75_ms"
    if abs(p - 0.9) < 1e-9:
        return "p90_ms"
    if abs(p - 0.95) < 1e-9:
        return "p95_ms"
    if abs(p - 0.99) < 1e-9:
        return "p99_ms"
    if abs(p - 0.997) < 1e-9:
        return "p997_ms"
    return "p99_ms"


def insert_summary(lines, run_id, row, scope, operation):
    fields = [
        "run_id", "scenario", "phase", "scope", "operation", "requests", "duration_s", "rps",
        "rps_median", "rps_min", "rps_max", "errors_unexpected", "errors_business", "errors_injected",
        "error_rate_pct", "unexpected_error_rate_pct", "mean_ms", "stdev_ms", "min_ms", "max_ms",
        "p50_ms", "p75_ms", "p90_ms", "p95_ms", "p99_ms", "p997_ms", "vus_median", "vus_max",
    ]
    values = [
        q(run_id), q(row.get("scenario")), q(row.get("phase")), q(scope), q(operation),
        to_int(row.get("requests")), to_float(row.get("duration_s")), to_float(row.get("rps")),
        to_float(row.get("rps_median")), to_float(row.get("rps_min")), to_float(row.get("rps_max")),
        to_int(row.get("errors_unexpected")), to_int(row.get("errors_business")),
        to_int(row.get("errors_injected")), to_float(row.get("error_rate_pct")),
        to_float(row.get("unexpected_error_rate_pct")), to_float(row.get("mean_ms")),
        to_float(row.get("stdev_ms")), to_float(row.get("min_ms")), to_float(row.get("max_ms")),
        to_float(row.get("p50_ms")), to_float(row.get("p75_ms")), to_float(row.get("p90_ms")),
        to_float(row.get("p95_ms")), to_float(row.get("p99_ms")), to_float(row.get("p997_ms")),
        to_float(row.get("vus_median")), to_float(row.get("vus_max")),
    ]
    lines.append(
        f"INSERT INTO lt_scenario_summary ({', '.join(fields)}) VALUES ({', '.join(map(str, values))});"
    )


def insert_resource(lines, run_id, row):
    fields = [
        "run_id", "scenario", "phase", "component", "samples",
        "cpu_min", "cpu_max", "cpu_median", "cpu_mean",
        "ram_min", "ram_max", "ram_median", "ram_mean", "ram_limit",
        "disk_read_min", "disk_read_max", "disk_read_median", "disk_read_mean",
        "disk_write_min", "disk_write_max", "disk_write_median", "disk_write_mean",
        "net_rx_min", "net_rx_max", "net_rx_median", "net_rx_mean",
        "net_tx_min", "net_tx_max", "net_tx_median", "net_tx_mean", "pids_max",
    ]
    cpu = row.get("cpu_percent") or {}
    ram = row.get("ram_bytes") or {}
    dr = row.get("disk_read_bps") or {}
    dw = row.get("disk_write_bps") or {}
    rx = row.get("net_rx_bps") or {}
    tx = row.get("net_tx_bps") or {}
    values = [
        q(run_id), q(row.get("scenario") or "all"), q(row.get("phase") or "all"),
        q(row.get("component")), to_int(row.get("samples")),
        to_float(cpu.get("min")), to_float(cpu.get("max")), to_float(cpu.get("median")), to_float(cpu.get("mean")),
        to_float(ram.get("min")), to_float(ram.get("max")), to_float(ram.get("median")), to_float(ram.get("mean")),
        to_float(row.get("ram_limit_bytes")),
        to_float(dr.get("min")), to_float(dr.get("max")), to_float(dr.get("median")), to_float(dr.get("mean")),
        to_float(dw.get("min")), to_float(dw.get("max")), to_float(dw.get("median")), to_float(dw.get("mean")),
        to_float(rx.get("min")), to_float(rx.get("max")), to_float(rx.get("median")), to_float(rx.get("mean")),
        to_float(tx.get("min")), to_float(tx.get("max")), to_float(tx.get("median")), to_float(tx.get("mean")),
        to_int(row.get("pids_max")),
    ]
    lines.append(
        f"INSERT INTO lt_resources ({', '.join(fields)}) VALUES ({', '.join(map(str, values))});"
    )


if __name__ == "__main__":
    main()
