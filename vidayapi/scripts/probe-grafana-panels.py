#!/usr/bin/env python3
"""ЛР4 — прогон всех SQL-панелей дашборда через Grafana /api/ds/query.

Имитирует поведение Grafana: подставляет $scenario/$run/$workload и выполняет
SQL каждой панели. Показывает ошибки плагина и панели, которые вернули 0 строк.

Использование:
  python3 scripts/probe-grafana-panels.py <run-id> <scenario> [workload]

workload (auth/public) выбирает файл
docker/grafana/provisioning/dashboards/loadtest-<workload>.json.
"""
import json
import subprocess
import sys

DEFAULT_WORKLOAD = "auth"
CREDS = "admin:admin"
URL = "http://localhost:3000/api/ds/query"
# 90 дней — чтобы проверять и старые прогоны, а не только последние сутки.
FROM = "now-90d"


def grafana_query(datasource, raw_sql, fmt="table"):
    payload = {
        "queries": [{
            "refId": "A",
            "datasource": datasource,
            "editorMode": "code",
            "format": fmt,
            "rawQuery": True,
            "rawSql": raw_sql,
            "maxDataPoints": 4000,
        }],
        "from": FROM,
        "to": "now",
    }
    p = subprocess.run(
        ["curl", "-s", "-u", CREDS, "-H", "Content-Type: application/json",
         "-d", json.dumps(payload), URL],
        capture_output=True, text=True, timeout=90)
    try:
        d = json.loads(p.stdout)
    except Exception:
        return "BADJSON", 0, p.stdout[:200]
    if d.get("results", {}).get("A", {}).get("error"):
        return "ERROR", 0, d["results"]["A"]["error"][:400]
    frames = d["results"]["A"].get("frames", [])
    rows = 0
    for f in frames:
        n = (f.get("data") or {}).get("values", [])
        rows = max(rows, len(n[0]) if n else 0)
    return "OK", rows, ""


def main():
    run = sys.argv[1]
    scenario = sys.argv[2] if len(sys.argv) > 2 else "ramp"
    workload = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_WORKLOAD
    dash_path = f"docker/grafana/provisioning/dashboards/loadtest-{workload}.json"
    dash = json.load(open(dash_path))["dashboard"]
    print(f"=== run={run} scenario={scenario} workload={workload} ===")
    problems = 0
    for panel in dash["panels"]:
        if panel.get("type") in ("row", "text"):
            continue
        for t in panel.get("targets", []):
            if (t.get("datasource") or {}).get("uid") != "benchmark_pg":
                continue
            sql = (t.get("rawSql", "")
                   .replace("$scenario", scenario)
                   .replace("$run", run)
                   .replace("$workload", workload))
            status, rows, err = grafana_query(t["datasource"], sql, t.get("format", "table"))
            flag = ""
            # Live-панели Prometheus пропускаем; SQL-панели должны вернуть данные.
            if status != "OK" or rows == 0:
                problems += 1
                flag = "  <<<"
            print(f"  [{panel['id']:3d}] {panel['type']:9s} {status:8s} rows={rows:4d} "
                  f"{panel['title'][:70]}{flag}")
            if err:
                print(f"        err: {err}")
    print(f"проблемных панелей: {problems}")


if __name__ == "__main__":
    main()
