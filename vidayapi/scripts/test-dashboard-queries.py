#!/usr/bin/env python3
"""ЛР4 — проверка SQL всех отчётных панелей дашборда Grafana.

Берёт запросы из generate-loadtest-dashboard.py, подставляет $run/$scenario
как Grafana и исполняет их в benchmark-db. Печатает ошибки и число строк.

    python3 scripts/test-dashboard-queries.py                 # auth: ramp + stress
    python3 scripts/test-dashboard-queries.py stress          # один тип прогона
    DASHBOARD_WORKLOAD=public python3 scripts/test-dashboard-queries.py ramp
"""
import importlib.util
import os
import subprocess
import sys

os.environ.setdefault("DASHBOARD_WORKLOAD", "auth")
spec = importlib.util.spec_from_file_location(
    "g", "scripts/generate-loadtest-dashboard.py")
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)

# Тип прогона → эталонный run для проверки SQL.
CASES = {
    "ramp": "lr4-v2-matrix-c1-r1024-co300",
    "stress": "lr4-good-c1-r256-h64-stress",
}

QUERIES = {
    "RUNS": g.RUNS_SQL,
    "KPI": g.KPI_SQL,
    "LATENCY": g.LATENCY_SQL,
    "RPS": g.RPS_SQL,
    "ERRORS": g.ERRORS_SQL,
    "COMPLETION": g.COMPLETION_SQL,
    "CUMULATIVE": g.CUMULATIVE_COMPLETION_SQL,
    "CROSS": g.CROSS_SQL,
    "FAILURE_RATE": g.FAILURE_RATE_SQL,
    "PEAK_FAILURE": g.PEAK_FAILURE_SQL,
    "FAILURE_WINDOWS": g.FAILURE_WINDOWS_SQL,
    "ADAPTIVE_DETAILS": g.ADAPTIVE_DETAILS_SQL,
    "CDF_TS": g.CDF_TS_SQL,
    "HIST_TS": g.HIST_TS_SQL,
    "OPERATIONS": g.OPERATIONS_SQL,
    "TOP_OPS": g.TOP_OPS_SQL,
    "RESOURCE_CPU": g.RESOURCE_CPU_SQL,
    "RESOURCE_RAM": g.RESOURCE_RAM_SQL,
    "RESOURCE_SUMMARY": g.RESOURCE_SUMMARY_SQL,
    "RESOURCE_PHASES": g.RESOURCE_PHASES_SQL,
    "HARDWARE": g.HARDWARE_SQL,
    "LOAD_CONFIG": g.LOAD_CONFIG_SQL,
    "CONFIG": g.CONFIG_SQL,
    "ENV": g.ENV_SQL,
    "FINDINGS": g.FINDINGS_SQL,
}

VAR_QUERIES = {
    "var:scenario": (
        "SELECT DISTINCT scenario FROM lt_runs "
        f"WHERE COALESCE(config->>'workload', 'auth') = '{g.WORKLOAD}' ORDER BY 1"
    ),
    "var:run": (
        "SELECT r.run_id AS \"__value\", "
        "r.run_id || ' · CPU=' || COALESCE(r.config->>'api_cpus', '?') AS \"__text\" "
        "FROM lt_runs r WHERE "
        f"COALESCE(r.config->>'workload', 'auth') = '{g.WORKLOAD}' "
        "AND r.scenario = '$scenario' ORDER BY r.started_at DESC"
    ),
}


def subst(sql, run, scenario):
    return (sql.replace("$scenario", scenario)
               .replace("$run", run)
               .replace("$workload", g.WORKLOAD)
               .replace("$__timeFilter(bucket_ts)",
                        "bucket_ts BETWEEN now() - interval '90 days' AND now()")
               .replace("$__timeFilter(ts)",
                        "ts BETWEEN now() - interval '90 days' AND now()"))


def run_sql(sql):
    p = subprocess.run(
        ["docker", "exec", "-i", "viday_bench_postgres",
         "psql", "-U", "benchmark_user", "-d", "benchmark", "-X", "-A", "-t"],
        input=sql, capture_output=True, text=True, timeout=60)
    return p.returncode, p.stdout.strip(), p.stderr.strip()


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    failures = 0
    for scenario, run in CASES.items():
        if only and only != scenario:
            continue
        print(f"\n=== {scenario}: run={run} ===")
        for qname, q in list(QUERIES.items()) + list(VAR_QUERIES.items()):
            rc, out, err = run_sql(subst(q, run, scenario))
            rows = len([line for line in out.splitlines() if line.strip()]) if rc == 0 else 0
            status = "OK" if rc == 0 else "SQL ERROR"
            if rc == 0 and rows == 0:
                status = "EMPTY"
            if rc != 0 or rows == 0:
                failures += 1
                print(f"  {qname:18s} {status:10s} rows={rows} <<<")
            if rc != 0:
                print(f"    err: {err[:300]}")
        print(f"  проверено запросов: {len(QUERIES) + len(VAR_QUERIES)}")
    print(f"\nитого проблемных запросов: {failures}")


if __name__ == "__main__":
    main()
