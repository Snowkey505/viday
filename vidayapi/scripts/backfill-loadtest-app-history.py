#!/usr/bin/env python3
"""Одноразовый перенос старых Actuator-рядов Prometheus в app-samples.jsonl."""
import datetime
import json
import os
import subprocess
import urllib.parse
import urllib.request

RUNS_SQL = "SELECT run_id, extract(epoch FROM started_at), extract(epoch FROM finished_at) FROM lt_runs ORDER BY started_at"
GAUGES = {
    "threads_live": ("jvm_threads_live_threads", lambda labels: True),
    "threads_daemon": ("jvm_threads_daemon_threads", lambda labels: True),
    "threads_runnable": ("jvm_threads_states_threads", lambda labels: labels.get("state") == "runnable"),
    "threads_waiting": ("jvm_threads_states_threads", lambda labels: labels.get("state") == "waiting"),
    "requests_in_flight": ("http_server_requests_active_seconds_gcount", lambda labels: True),
    "executor_active": ("executor_active_threads", lambda labels: True),
    "executor_pool": ("executor_pool_size_threads", lambda labels: True),
    "heap_bytes": ("jvm_memory_used_bytes", lambda labels: labels.get("area") == "heap"),
    "hikari_active": ("hikaricp_connections_active", lambda labels: True),
    "hikari_idle": ("hikaricp_connections_idle", lambda labels: True),
    "tomcat_active": ("tomcat_sessions_active_current_sessions", lambda labels: True),
}
COUNTERS = {
    "_http_total": ("http_server_requests_seconds_count", lambda labels: "actuator" not in labels.get("uri", "")),
    "_http_5xx": ("http_server_requests_seconds_count", lambda labels: "actuator" not in labels.get("uri", "") and labels.get("status", "").startswith("5")),
    "_gc_total": ("jvm_gc_pause_seconds_sum", lambda labels: True),
    "_rejected_total": ("tomcat_sessions_rejected_sessions_total", lambda labels: True),
}


def query(run_id, metric, start, end):
    params = urllib.parse.urlencode({
        "query": f'{metric}{{run_id="{run_id}"}}', "start": start, "end": end, "step": 5,
    })
    with urllib.request.urlopen("http://localhost:9090/api/v1/query_range?" + params, timeout=30) as response:
        data = json.load(response)
    if data.get("status") != "success":
        raise RuntimeError(data)
    return data["data"]["result"]


def iso(ts):
    return datetime.datetime.fromtimestamp(ts, datetime.timezone.utc).isoformat().replace("+00:00", "Z")


def main():
    runs = subprocess.check_output([
        "docker", "exec", "viday_bench_postgres", "psql", "-At", "-F", "\t",
        "-U", "benchmark_user", "-d", "benchmark", "-c", RUNS_SQL,
    ], text=True)
    output = "loadtest/runtime/app-samples.jsonl"
    completed = []
    with open(output, "a", encoding="utf-8") as fh:
        for line in runs.splitlines():
            run_id, start, end = line.split("\t")
            report_path = f"loadtest/results/{run_id}/report.json"
            if not os.path.exists(report_path):
                continue
            with open(report_path, encoding="utf-8") as report_file:
                if json.load(report_file).get("app_samples"):
                    continue
            rows = {}
            try:
                for field, (metric, predicate) in {**GAUGES, **COUNTERS}.items():
                    for series in query(run_id, metric, float(start) - 10, float(end) + 10):
                        if not predicate(series.get("metric") or {}):
                            continue
                        for ts, value in series.get("values") or []:
                            row = rows.setdefault(float(ts), {"run_id": run_id, "ts": float(ts)})
                            row[field] = row.get(field, 0.0) + float(value)
            except Exception as exc:
                print(run_id, "backfill error", exc)
                continue
            if not rows:
                print(run_id, "no Prometheus history")
                continue
            previous = None
            for ts in sorted(rows):
                row = rows[ts]
                current = {key: row.get(key, 0.0) for key in COUNTERS}

                def rate(key):
                    if previous is None or ts <= previous[0]:
                        return 0.0
                    return max(0.0, current[key] - previous[1][key]) / (ts - previous[0])

                result = {key: row.get(key, 0.0) for key in GAUGES}
                result.update({
                    "run_id": run_id, "ts": iso(ts), "http_rps": rate("_http_total"),
                    "http_5xx_rps": rate("_http_5xx"), "gc_pause_rate": rate("_gc_total"),
                    "tomcat_rejected_rate": rate("_rejected_total"),
                })
                fh.write(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n")
                previous = (ts, current)
            fh.flush()
            completed.append(run_id)
            print(run_id, "samples", len(rows))
    print("BACKFILLED", " ".join(completed))


if __name__ == "__main__":
    main()
