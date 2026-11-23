#!/usr/bin/env python3
"""ЛР4 — разбор NDJSON генератора + exporter в отчёт и данные для Grafana.

Вход:
  loadtest/results/<run-id>/raw.json       — NDJSON k6 или Kotlin-генератора;
  loadtest/runtime/container-samples.jsonl — сырые сэмплы ресурсов;
  loadtest/results/<run-id>/metadata.json  — конфигурация/окружение прогона.

Выход:
  report.json, resource-summary.json, report.md
  SQL-импорт делает scripts/import-loadtest-to-db.py.

Почему не только k6 summary: нужны p99.7, CDF, гистограмма, покадровые ряды,
разделение ожидаемых 4xx (инъекции ошибок) и настоящих отказов 5xx/timeout,
а также ресурсы по компонентам за те же интервалы времени.
"""
import argparse
import json
import math
import os
import platform
import statistics
import subprocess
import sys
from bisect import bisect_right
from collections import defaultdict
from datetime import datetime, timezone

PERCENTILES = [0.5, 0.75, 0.9, 0.95, 0.99, 0.997]
CDF_FRACTIONS = [i / 100.0 for i in range(0, 101)] + [0.999]
# Окно для временных рядов и статистики RPS. 1-секундные бакеты у k6 дают
# неестественные всплески из-за пачечной записи NDJSON; 5 с — компромисс между
# детализацией и устойчивостью min/median/max.
BUCKET_SECONDS = 5


def parse_time(value):
    if not value:
        return 0.0
    s = str(value).replace("Z", "+00:00")
    # k6 печатает наносекунды; datetime.fromisoformat принимает до 6 знаков.
    if "." in s:
        head, rest = s.split(".", 1)
        if "+" in rest:
            frac, tz = rest.split("+", 1)
            frac = frac[:6]
            s = f"{head}.{frac}+{tz}"
        else:
            s = f"{head}.{rest[:6]}"
    try:
        return datetime.fromisoformat(s).timestamp()
    except ValueError:
        return 0.0


def iso(ts):
    if ts is None:
        return None
    return datetime.fromtimestamp(ts, timezone.utc).isoformat().replace("+00:00", "Z")


def percentile(sorted_values, q):
    if not sorted_values:
        return 0.0
    if q <= 0:
        return float(sorted_values[0])
    if q >= 1:
        return float(sorted_values[-1])
    pos = (len(sorted_values) - 1) * q
    lo = int(math.floor(pos))
    hi = min(lo + 1, len(sorted_values) - 1)
    frac = pos - lo
    return float(sorted_values[lo] * (1.0 - frac) + sorted_values[hi] * frac)


def pct_map(values):
    ordered = sorted(values)
    return {q: percentile(ordered, q) for q in PERCENTILES}


def safe_mean(values):
    return float(statistics.fmean(values)) if values else 0.0


def safe_stdev(values):
    return float(statistics.pstdev(values)) if len(values) > 1 else 0.0


def safe_median(values):
    return float(statistics.median(values)) if values else 0.0


def latency_summary(values):
    ordered = sorted(values)
    return {
        "mean_ms": safe_mean(values),
        "stdev_ms": safe_stdev(values),
        "min_ms": float(ordered[0]) if ordered else 0.0,
        "max_ms": float(ordered[-1]) if ordered else 0.0,
        "p50_ms": percentile(ordered, 0.5),
        "p75_ms": percentile(ordered, 0.75),
        "p90_ms": percentile(ordered, 0.90),
        "p95_ms": percentile(ordered, 0.95),
        "p99_ms": percentile(ordered, 0.99),
        "p997_ms": percentile(ordered, 0.997),
    }


def read_metadata(path):
    if path and os.path.exists(path):
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    return {}


def read_raw(path):
    """Возвращает (records, vus_points).

    record = (ts, duration_ms, scenario, phase, operation, status, unexpected, business, injected)
    """
    records = []
    vus_points = []
    total_lines = 0
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            total_lines += 1
            if not line.strip():
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if obj.get("type") != "Point":
                continue
            metric = obj.get("metric")
            data = obj.get("data") or {}
            if metric == "vus":
                ts = parse_time(data.get("time"))
                try:
                    vus_points.append((ts, float(data.get("value") or 0)))
                except (TypeError, ValueError):
                    pass
                continue
            if metric != "http_req_duration":
                continue
            tags = data.get("tags") or {}
            operation = tags.get("operation")
            if not operation:
                # setup() и служебные запросы не входят в пользовательский сценарий.
                continue
            ts = parse_time(data.get("time"))
            try:
                duration = float(data.get("value") or 0)
            except (TypeError, ValueError):
                duration = 0.0
            scenario = str(tags.get("scenario") or "default")
            phase = str(tags.get("phase") or scenario)
            status_raw = tags.get("status")
            try:
                status = int(status_raw or 0)
            except (TypeError, ValueError):
                status = 0
            injected = str(tags.get("injected_error", "")).lower() == "true"
            unexpected = status == 0 or status >= 500
            business = (400 <= status < 500) and not injected
            records.append((ts, duration, scenario, phase, str(operation), status,
                            bool(unexpected), bool(business), injected))
    vus_points.sort(key=lambda x: x[0])
    return records, vus_points, total_lines


def vu_at(vus_times, vus_values, ts):
    if not vus_times:
        return 0.0
    idx = bisect_right(vus_times, ts) - 1
    if idx < 0:
        return vus_values[0]
    return vus_values[idx]


def bucket_timeseries(records, vus_times, vus_values):
    buckets = defaultdict(list)
    for rec in records:
        buckets[int(math.floor(rec[0] / BUCKET_SECONDS))].append(rec)
    rows = []
    for bucket in sorted(buckets):
        group = buckets[bucket]
        window_start = float(bucket * BUCKET_SECONDS)
        durations = [r[1] for r in group]
        vus = vu_at(vus_times, vus_values, window_start + BUCKET_SECONDS / 2.0)
        rows.append({
            "bucket_ts": iso(window_start),
            "requests": len(group),
            "rps": float(len(group)) / BUCKET_SECONDS,
            "vus": vus,
            "errors_unexpected": sum(1 for r in group if r[6]),
            "errors_business": sum(1 for r in group if r[7]),
            "errors_injected": sum(1 for r in group if r[8]),
            "mean_ms": safe_mean(durations),
            "p50_ms": percentile(sorted(durations), 0.5),
            "p75_ms": percentile(sorted(durations), 0.75),
            "p90_ms": percentile(sorted(durations), 0.9),
            "p95_ms": percentile(sorted(durations), 0.95),
            "p99_ms": percentile(sorted(durations), 0.99),
            "p997_ms": percentile(sorted(durations), 0.997),
            "max_ms": max(durations),
        })
    return rows


def histogram(values, bins=30):
    if not values:
        return []
    ordered = sorted(values)
    upper = percentile(ordered, 0.999)
    upper = max(upper, ordered[0], 1.0)
    if upper <= ordered[0]:
        upper = ordered[-1] if ordered[-1] > ordered[0] else ordered[0] + 1.0
    width = upper / bins
    counts = [0] * bins
    for value in values:
        idx = min(bins - 1, max(0, int(value / width)))
        counts[idx] += 1
    return [
        {
            "bucket_index": i,
            "from_ms": round(i * width, 4),
            "to_ms": round((i + 1) * width, 4),
            "count": counts[i],
        }
        for i in range(bins)
    ]


def summarize_group(records, vus_times, vus_values):
    durations = [r[1] for r in records]
    start = min(r[0] for r in records)
    end = max(r[0] for r in records)
    duration_s = max(1e-9, end - start)
    vus_values_in_window = [vu_at(vus_times, vus_values, r[0]) for r in records]
    latency = latency_summary(durations)
    errors_unexpected = sum(1 for r in records if r[6])
    errors_business = sum(1 for r in records if r[7])
    errors_injected = sum(1 for r in records if r[8])
    rps_values = [len(group) / BUCKET_SECONDS for group in _bucket_groups(records)]
    return {
        "started_at": iso(start),
        "finished_at": iso(end),
        "duration_s": duration_s,
        "requests": len(records),
        "rps": len(records) / duration_s,
        "rps_median": safe_median(rps_values),
        "rps_min": min(rps_values) if rps_values else 0.0,
        "rps_max": max(rps_values) if rps_values else 0.0,
        "errors_unexpected": errors_unexpected,
        "errors_business": errors_business,
        "errors_injected": errors_injected,
        "error_rate_pct": (errors_unexpected / len(records) * 100.0) if records else 0.0,
        "unexpected_error_rate_pct": (errors_unexpected / len(records) * 100.0) if records else 0.0,
        "business_error_rate_pct": (errors_business / len(records) * 100.0) if records else 0.0,
        "injected_share_pct": (errors_injected / len(records) * 100.0) if records else 0.0,
        "vus_median": safe_median(vus_values_in_window),
        "vus_max": max(vus_values_in_window) if vus_values_in_window else 0.0,
        **latency,
    }


def _bucket_groups(records):
    groups = defaultdict(list)
    for rec in records:
        groups[int(math.floor(rec[0] / BUCKET_SECONDS))].append(rec)
    return [groups[k] for k in sorted(groups)]


def build_ramp_curve(records, vus_times, vus_values):
    grouped = defaultdict(list)
    for rec in records:
        vus = vu_at(vus_times, vus_values, rec[0])
        target = int(round(vus / 10.0) * 10)
        grouped[target].append(rec)
    curve = []
    for target in sorted(grouped):
        group = grouped[target]
        if len(group) < 5:
            continue
        durations = sorted(r[1] for r in group)
        seconds = len({int(math.floor(r[0])) for r in group})
        requests = len(group)
        unexpected = sum(1 for r in group if r[6])
        business = sum(1 for r in group if r[7])
        p95 = percentile(durations, 0.95)
        error_rate = unexpected / requests * 100.0
        curve.append({
            "target_vus": target,
            "requests": requests,
            "rps": requests / max(1, seconds),
            "errors_unexpected": unexpected,
            "errors_business": business,
            "unexpected_error_rate_pct": error_rate,
            "mean_ms": safe_mean([r[1] for r in group]),
            "p95_ms": p95,
            "p99_ms": percentile(durations, 0.99),
            "stable": error_rate < 1.0 and p95 < 500.0,
        })
    return curve


def compute_recovery(records_by_phase):
    if not {"baseline", "overload", "recovery"}.issubset(records_by_phase):
        return None
    baseline = records_by_phase["baseline"]
    recovery = records_by_phase["recovery"]
    if not baseline or not recovery:
        return None
    baseline_end = max(r[0] for r in baseline)
    baseline_reference = [r for r in baseline if r[0] >= baseline_end - 60]
    baseline_p95 = percentile(sorted(r[1] for r in baseline_reference or baseline), 0.95)
    threshold = max(baseline_p95 * 1.5, baseline_p95 + 20.0)
    recovery_start = min(r[0] for r in recovery)
    recovery_end = max(r[0] for r in recovery)
    windows = []
    cursor = recovery_start
    while cursor < recovery_end:
        window = [r for r in recovery if cursor <= r[0] < cursor + 10]
        if len(window) >= 20:
            p95 = percentile(sorted(r[1] for r in window), 0.95)
            errors = sum(1 for r in window if r[6])
            windows.append({
                "start": cursor,
                "p95_ms": p95,
                "error_rate_pct": errors / len(window) * 100.0,
                "requests": len(window),
            })
        cursor += 10
    recovered_at = None
    for i, window in enumerate(windows):
        ok = window["p95_ms"] <= threshold and window["error_rate_pct"] < 1.0
        next_ok = i + 1 >= len(windows) or (
            windows[i + 1]["p95_ms"] <= threshold and windows[i + 1]["error_rate_pct"] < 1.0
        )
        if ok and next_ok:
            recovered_at = window["start"]
            break
    return {
        "baseline_p95_ms": baseline_p95,
        "recovery_threshold_ms": threshold,
        "recovery_start": iso(recovery_start),
        "recovery_seconds": None if recovered_at is None else max(0.0, recovered_at - recovery_start),
        "windows": [{**w, "start": iso(w["start"])} for w in windows],
        "recovered": recovered_at is not None,
    }


def parse_size_to_bytes(text):
    if not text:
        return 0.0
    text = str(text).strip()
    units = {
        "B": 1, "kB": 1000, "KB": 1000, "KiB": 1024,
        "MB": 1000 ** 2, "MiB": 1024 ** 2,
        "GB": 1000 ** 3, "GiB": 1024 ** 3,
        "TB": 1000 ** 4, "TiB": 1024 ** 4,
    }
    for unit in sorted(units, key=len, reverse=True):
        if text.endswith(unit):
            try:
                return float(text[:-len(unit)]) * units[unit]
            except ValueError:
                return 0.0
    try:
        return float(text)
    except ValueError:
        return 0.0


def cpuset_size(value):
    size = 0
    for part in str(value or "").split(","):
        if "-" in part:
            start, end = part.split("-", 1)
            size += int(end) - int(start) + 1
        elif part.strip():
            size += 1
    return size


def resource_cpu_limits(metadata):
    config = metadata.get("config") or {}
    cpusets = (metadata.get("environment") or {}).get("cpusets") or {}
    limits = {"api": float(config.get("api_cpus") or 2)}
    for component, key in [("postgres", "postgres"), ("redis", "redis"), ("minio", "minio"),
                           ("generator", "generator"), ("k6", "generator")]:
        size = cpuset_size(cpusets.get(key))
        if size:
            limits[component] = float(size)
    return limits


def read_app_samples(path, run_id, run_start, run_end):
    if not path or not os.path.exists(path):
        return []
    samples = []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            ts = parse_time(obj.get("ts"))
            if obj.get("run_id") != run_id or ts < run_start - 15 or ts > run_end + 15:
                continue
            samples.append({**obj, "ts": iso(ts)})
    samples.sort(key=lambda row: row["ts"])
    return samples


def read_resources(path, run_id, run_start, run_end, cpu_limits=None):
    """Возвращает {component: [samples]}; CPU нормализован к лимиту контейнера."""
    cpu_limits = cpu_limits or {}
    by_component = defaultdict(list)
    if not path or not os.path.exists(path):
        return by_component
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if obj.get("run_id") != run_id:
                continue
            ts = parse_time(obj.get("ts"))
            if ts < run_start - 15 or ts > run_end + 15:
                continue
            component = obj.get("component") or "unknown"
            raw_cpu = float(obj.get("cpu_percent") or 0.0)
            normalized_cpu = obj.get("cpu_percent_normalized")
            cpu_pct = (
                float(normalized_cpu)
                if normalized_cpu is not None
                else min(100.0, raw_cpu / max(cpu_limits.get(component, 1.0), 0.01))
            )
            by_component[component].append({
                "ts": ts,
                "cpu_pct": cpu_pct,
                "ram_bytes": float(obj.get("memory_working_set_bytes") or 0.0),
                "ram_limit_bytes": float(obj.get("memory_limit_bytes") or 0.0),
                "block_read_bytes": float(obj.get("block_read_bytes") or 0.0),
                "block_write_bytes": float(obj.get("block_write_bytes") or 0.0),
                "net_rx_bytes": float(obj.get("network_rx_bytes") or 0.0),
                "net_tx_bytes": float(obj.get("network_tx_bytes") or 0.0),
                "pids": int(obj.get("pids") or 0),
            })
    for component, samples in by_component.items():
        samples.sort(key=lambda x: x["ts"])
        prev = None
        for sample in samples:
            if prev is None:
                sample["disk_read_bps"] = 0.0
                sample["disk_write_bps"] = 0.0
                sample["net_rx_bps"] = 0.0
                sample["net_tx_bps"] = 0.0
            else:
                dt = sample["ts"] - prev["ts"]
                if dt <= 0:
                    dt = 1e-9
                sample["disk_read_bps"] = max(0.0, sample["block_read_bytes"] - prev["block_read_bytes"]) / dt
                sample["disk_write_bps"] = max(0.0, sample["block_write_bytes"] - prev["block_write_bytes"]) / dt
                sample["net_rx_bps"] = max(0.0, sample["net_rx_bytes"] - prev["net_rx_bytes"]) / dt
                sample["net_tx_bps"] = max(0.0, sample["net_tx_bytes"] - prev["net_tx_bytes"]) / dt
            prev = sample
    return by_component


def resource_summary(samples):
    if not samples:
        return None
    def stats(key):
        values = [s[key] for s in samples if s.get(key) is not None]
        if not values:
            return {"min": 0.0, "max": 0.0, "median": 0.0, "mean": 0.0}
        return {
            "min": min(values),
            "max": max(values),
            "median": safe_median(values),
            "mean": safe_mean(values),
        }
    return {
        "samples": len(samples),
        "cpu_percent": stats("cpu_pct"),
        "ram_bytes": stats("ram_bytes"),
        "ram_mib": {
            "min": stats("ram_bytes")["min"] / 1024 ** 2,
            "max": stats("ram_bytes")["max"] / 1024 ** 2,
            "median": stats("ram_bytes")["median"] / 1024 ** 2,
            "mean": stats("ram_bytes")["mean"] / 1024 ** 2,
        },
        "ram_limit_bytes": max((s.get("ram_limit_bytes") or 0) for s in samples),
        "disk_read_bps": stats("disk_read_bps"),
        "disk_write_bps": stats("disk_write_bps"),
        "net_rx_bps": stats("net_rx_bps"),
        "net_tx_bps": stats("net_tx_bps"),
        "pids_max": max((s.get("pids") or 0) for s in samples),
    }


def resource_trend(samples):
    if len(samples) < 10:
        return None
    t0 = samples[0]["ts"]
    xs = [(s["ts"] - t0) / 60.0 for s in samples]
    def slope(values):
        n = len(xs)
        mx = sum(xs) / n
        my = sum(values) / n
        denom = sum((x - mx) ** 2 for x in xs)
        if denom == 0:
            return 0.0
        return sum((x - mx) * (y - my) for x, y in zip(xs, values)) / denom
    return {
        "ram_mib_per_min": slope([s["ram_bytes"] / 1024 ** 2 for s in samples]),
        "cpu_pct_per_min": slope([s["cpu_pct"] for s in samples]),
    }


def environment_snapshot():
    env = {
        "hostname": platform.node(),
        "platform": platform.platform(),
        "python": platform.python_version(),
        "cpu_count": os.cpu_count(),
    }
    try:
        with open("/proc/meminfo", "r", encoding="utf-8") as fh:
            mem = {}
            for line in fh:
                key, value = line.split(":", 1)
                mem[key] = value.strip()
            env["memory_total"] = mem.get("MemTotal")
    except OSError:
        pass
    try:
        env["cpu_model"] = next(
            line.split(":", 1)[1].strip()
            for line in open("/proc/cpuinfo", "r", encoding="utf-8")
            if line.startswith("model name")
        )
    except (OSError, StopIteration):
        pass
    try:
        env["docker_version"] = subprocess.check_output(
            ["docker", "version", "--format", "{{.Server.Version}}"],
            text=True, stderr=subprocess.DEVNULL, timeout=5,
        ).strip()
    except (OSError, subprocess.SubprocessError):
        pass
    return env


def build_report(args, metadata, records, vus_points):
    if not records:
        raise SystemExit("no http_req_duration points with operation tag in raw.json")
    records.sort(key=lambda r: r[0])
    vus_times = [p[0] for p in vus_points]
    vus_values = [p[1] for p in vus_points]
    run_start = min(r[0] for r in records)
    run_end = max(r[0] for r in records)

    by_phase = defaultdict(list)
    for rec in records:
        by_phase[(rec[2], rec[3])].append(rec)
    phases = []
    for (scenario, phase), group in sorted(by_phase.items(), key=lambda kv: min(r[0] for r in kv[1])):
        summary = summarize_group(group, vus_times, vus_values)
        summary["scenario"] = scenario
        summary["phase"] = phase
        phases.append(summary)

    operations = []
    for (scenario, phase), group in sorted(by_phase.items(), key=lambda kv: min(r[0] for r in kv[1])):
        by_operation = defaultdict(list)
        for rec in group:
            by_operation[rec[4]].append(rec)
        for operation, op_group in sorted(by_operation.items()):
            summary = summarize_group(op_group, vus_times, vus_values)
            summary["scenario"] = scenario
            summary["phase"] = phase
            summary["operation"] = operation
            operations.append(summary)

    cdf = []
    histogram_rows = []
    for phase_summary in phases:
        scenario, phase = phase_summary["scenario"], phase_summary["phase"]
        group = by_phase[(scenario, phase)]
        for scope, operation, values in [("overall", "all", [r[1] for r in group])]:
            ordered = sorted(values)
            for fraction in CDF_FRACTIONS:
                cdf.append({
                    "scenario": scenario, "phase": phase, "scope": scope,
                    "operation": operation, "fraction": fraction,
                    "latency_ms": percentile(ordered, fraction),
                })
            for bucket in histogram(values):
                histogram_rows.append({
                    "scenario": scenario, "phase": phase, "scope": scope,
                    "operation": operation, **bucket,
                })
        by_operation = defaultdict(list)
        for rec in group:
            by_operation[rec[4]].append(rec)
        for operation, op_group in by_operation.items():
            if len(op_group) < 10:
                continue
            values = [r[1] for r in op_group]
            ordered = sorted(values)
            for fraction in CDF_FRACTIONS:
                cdf.append({
                    "scenario": scenario, "phase": phase, "scope": "operation",
                    "operation": operation, "fraction": fraction,
                    "latency_ms": percentile(ordered, fraction),
                })
            for bucket in histogram(values):
                histogram_rows.append({
                    "scenario": scenario, "phase": phase, "scope": "operation",
                    "operation": operation, **bucket,
                })

    timeseries = []
    for (scenario, phase), group in by_phase.items():
        for row in bucket_timeseries(group, vus_times, vus_values):
            row["scenario"] = scenario
            row["phase"] = phase
            row["operation"] = "all"
            timeseries.append(row)
        by_operation = defaultdict(list)
        for rec in group:
            by_operation[rec[4]].append(rec)
        for operation, op_group in by_operation.items():
            for row in bucket_timeseries(op_group, vus_times, vus_values):
                row["scenario"] = scenario
                row["phase"] = phase
                row["operation"] = operation
                timeseries.append(row)

    ramp_curve = build_ramp_curve(records, vus_times, vus_values) if args.scenario == "ramp" else []
    records_by_phase = {phase: group for (_scenario, phase), group in by_phase.items()}
    recovery = compute_recovery(records_by_phase) if args.scenario == "stress" else None

    resources = read_resources(
        args.samples, args.run_id, run_start, run_end, resource_cpu_limits(metadata),
    )
    app_samples = read_app_samples(args.app_samples, args.run_id, run_start, run_end)
    resource_components = {}
    resource_phases = []
    resource_samples = []
    for component, samples in sorted(resources.items()):
        resource_components[component] = resource_summary(samples)
        for sample in samples:
            resource_samples.append({
                "ts": iso(sample["ts"]),
                "component": component,
                "cpu_pct": sample["cpu_pct"],
                "ram_bytes": sample["ram_bytes"],
                "disk_read_bps": sample["disk_read_bps"],
                "disk_write_bps": sample["disk_write_bps"],
                "net_rx_bps": sample["net_rx_bps"],
                "net_tx_bps": sample["net_tx_bps"],
                "pids": sample["pids"],
            })
    for phase_summary in phases:
        start = parse_time(phase_summary["started_at"])
        end = parse_time(phase_summary["finished_at"])
        for component, samples in sorted(resources.items()):
            window = [s for s in samples if start <= s["ts"] <= end]
            summary = resource_summary(window)
            if summary:
                resource_phases.append({
                    "scenario": phase_summary["scenario"],
                    "phase": phase_summary["phase"],
                    "component": component,
                    **summary,
                })
    all_summary = []
    for component, samples in sorted(resources.items()):
        summary = resource_summary(samples)
        if summary:
            all_summary.append({"phase": "all", "component": component, **summary})

    findings = []
    metadata_config = metadata.get("config") or {}
    if metadata_config.get("early_stopped"):
        findings.append({
            "kind": "termination",
            "key": "early_stopped",
            "value": "true",
            "detail": metadata_config.get("termination_reason") or "failure threshold reached",
        })
    if ramp_curve:
        stable = [row for row in ramp_curve if row["stable"]]
        unstable = [row for row in ramp_curve if not row["stable"]]
        max_stable = max(stable, key=lambda r: r["target_vus"]) if stable else None
        first_unstable = min(
            (r for r in unstable if max_stable is None or r["target_vus"] > max_stable["target_vus"]),
            key=lambda r: r["target_vus"], default=None,
        )
        peak = max(ramp_curve, key=lambda r: r["rps"])
        if max_stable:
            findings.append({
                "kind": "ramp", "key": "max_stable_vus",
                "value": str(max_stable["target_vus"]),
                "detail": f"RPS={max_stable['rps']:.1f}, p95={max_stable['p95_ms']:.1f} ms, "
                          f"unexpected={max_stable['unexpected_error_rate_pct']:.2f}%",
            })
        if first_unstable:
            findings.append({
                "kind": "ramp", "key": "first_unstable_vus",
                "value": str(first_unstable["target_vus"]),
                "detail": f"RPS={first_unstable['rps']:.1f}, p95={first_unstable['p95_ms']:.1f} ms, "
                          f"unexpected={first_unstable['unexpected_error_rate_pct']:.2f}%",
            })
        findings.append({
            "kind": "ramp", "key": "peak_rps",
            "value": f"{peak['rps']:.2f}",
            "detail": f"observed at ~{peak['target_vus']} VUs, p95={peak['p95_ms']:.1f} ms",
        })
    if recovery:
        findings.append({
            "kind": "stress", "key": "recovery_seconds",
            "value": "none" if recovery["recovery_seconds"] is None else f"{recovery['recovery_seconds']:.0f}",
            "detail": f"baseline p95={recovery['baseline_p95_ms']:.1f} ms, "
                      f"threshold={recovery['recovery_threshold_ms']:.1f} ms",
        })
    # Тренд RAM/CPU — это «soak»-показатель только для soak-профилей; для ramp
    # и stress он всё равно полезен, но не должен называться soak (иначе в
    # отчёте ramp появляются выводы «soak/...»).
    trend_kind = "soak" if args.scenario in ("soak", "soak-errors") else "resource_trend"
    for component, samples in sorted(resources.items()):
        trend = resource_trend(samples)
        if trend:
            findings.append({
                "kind": trend_kind, "key": f"{component}.ram_mib_per_min",
                "value": f"{trend['ram_mib_per_min']:.4f}",
                "detail": f"CPU slope {trend['cpu_pct_per_min']:.4f} %/min over {len(samples)} samples",
            })

    summary = {
        "run_id": args.run_id,
        "title": metadata.get("title") or f"LR4 {args.scenario}",
        "profile": args.scenario,
        "scenario": args.scenario,
        "started_at": iso(run_start),
        "finished_at": iso(run_end),
        "duration_s": run_end - run_start,
        "config": metadata.get("config") or {},
        "environment": metadata.get("environment") or environment_snapshot(),
        "early_stopped": bool((metadata.get("config") or {}).get("early_stopped", False)),
        "termination_reason": (metadata.get("config") or {}).get("termination_reason", "planned duration reached"),
        "phases": phases,
        "operations": operations,
        "ramp_curve": ramp_curve,
        "recovery": recovery,
        "resources": {
            "components": resource_components,
            "all": all_summary,
            "phases": resource_phases,
        },
        "findings": findings,
        "percentiles": PERCENTILES,
        "raw_points": len(records),
        "vus_points": len(vus_points),
        "app_samples": app_samples,
    }
    # started_at из metadata точнее (включает setup/старт k6) — сохраняем оба.
    summary["k6_started_at"] = summary["started_at"]
    summary["started_at"] = metadata.get("started_at") or summary["started_at"]
    summary["finished_at"] = metadata.get("finished_at") or summary["finished_at"]
    return summary, phases, operations, cdf, histogram_rows, timeseries, ramp_curve, resource_phases, resource_samples


def write_markdown(summary, path):
    phases = summary["phases"]
    lines = []
    lines.append(f"# ЛР4 — нагрузочный прогон `{summary['run_id']}`")
    lines.append("")
    lines.append(f"- Профиль: **{summary['profile']}**")
    lines.append(f"- Начало: `{summary['started_at']}`")
    lines.append(f"- Конец: `{summary['finished_at']}`")
    lines.append(f"- Длительность: **{summary['duration_s']:.1f} c**")
    lines.append(f"- Запросов с измеренной латентностью: **{summary['raw_points']}**")
    lines.append("")
    lines.append("## Сводка по фазам")
    lines.append("")
    lines.append("| Фаза | Запросов | RPS | Отказы (5xx/timeout) | Бизнес-4xx | Injected | p50, мс | p75 | p90 | p95 | p99 | p99.7 |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for phase in phases:
        lines.append(
            f"| {phase['phase']} | {phase['requests']} | {phase['rps']:.1f} | "
            f"{phase['errors_unexpected']} ({phase['unexpected_error_rate_pct']:.2f}%) | "
            f"{phase['errors_business']} | {phase['errors_injected']} | "
            f"{phase['p50_ms']:.1f} | {phase['p75_ms']:.1f} | {phase['p90_ms']:.1f} | "
            f"{phase['p95_ms']:.1f} | {phase['p99_ms']:.1f} | {phase['p997_ms']:.1f} |"
        )
    lines.append("")
    lines.append("## Операции")
    for phase in phases:
        lines.append("")
        lines.append(f"### {phase['phase']}")
        lines.append("")
        lines.append("| Операция | Запросов | RPS | 5xx/timeout | 4xx | p50, мс | p95, мс | p99, мс |")
        lines.append("|---|---:|---:|---:|---:|---:|---:|---:|")
        ops = [o for o in summary["operations"] if o["scenario"] == phase["scenario"] and o["phase"] == phase["phase"]]
        for op in sorted(ops, key=lambda x: -x["requests"]):
            lines.append(
                f"| {op['operation']} | {op['requests']} | {op['rps']:.2f} | "
                f"{op['errors_unexpected']} | {op['errors_business']} | "
                f"{op['p50_ms']:.1f} | {op['p95_ms']:.1f} | {op['p99_ms']:.1f} |"
            )
    if summary["ramp_curve"]:
        lines.append("")
        lines.append("## Кривая ramp: заявленные VUs → RPS / p95 / ошибки")
        lines.append("")
        lines.append("| VUs (набл.) | Запросов | RPS | 5xx/timeout, % | p95, мс | p99, мс | Стабильно |")
        lines.append("|---:|---:|---:|---:|---:|---:|:---:|")
        for row in summary["ramp_curve"]:
            lines.append(
                f"| {row['target_vus']} | {row['requests']} | {row['rps']:.1f} | "
                f"{row['unexpected_error_rate_pct']:.2f} | {row['p95_ms']:.1f} | "
                f"{row['p99_ms']:.1f} | {'да' if row['stable'] else 'нет'} |"
            )
    if summary["recovery"]:
        rec = summary["recovery"]
        lines.append("")
        lines.append("## Восстановление после перегруза")
        lines.append("")
        lines.append(f"- Baseline p95: **{rec['baseline_p95_ms']:.1f} мс**")
        lines.append(f"- Порог восстановления: **{rec['recovery_threshold_ms']:.1f} мс**")
        lines.append(f"- Время восстановления: **{rec['recovery_seconds'] if rec['recovery_seconds'] is not None else 'не восстановилась'}**")
        lines.append("")
        lines.append("| Окно от начала recovery | Запросов | p95, мс | 5xx/timeout, % |")
        lines.append("|---|---:|---:|---:|")
        for window in rec["windows"][:30]:
            lines.append(
                f"| {window['start']} | {window['requests']} | {window['p95_ms']:.1f} | "
                f"{window['error_rate_pct']:.2f} |"
            )
    lines.append("")
    lines.append("## Ресурсы (min / max / median / mean)")
    lines.append("")
    lines.append("| Компонент | CPU, % | RAM, MiB | Disk read, MiB/s | Disk write, MiB/s | Net RX, MiB/s | Net TX, MiB/s | PIDs max |")
    lines.append("|---|---|---|---|---|---|---|---:|")
    for component, res in sorted(summary["resources"]["components"].items()):
        if not res:
            continue
        def fmt(stats, scale=1.0):
            return (f"{stats['min']/scale:.2f} / {stats['max']/scale:.2f} / "
                    f"{stats['median']/scale:.2f} / {stats['mean']/scale:.2f}")
        lines.append(
            f"| {component} | {fmt(res['cpu_percent'])} | {fmt(res['ram_bytes'], 1024**2)} | "
            f"{fmt(res['disk_read_bps'], 1024**2)} | {fmt(res['disk_write_bps'], 1024**2)} | "
            f"{fmt(res['net_rx_bps'], 1024**2)} | {fmt(res['net_tx_bps'], 1024**2)} | {res['pids_max']} |"
        )
    lines.append("")
    lines.append("## Findings")
    lines.append("")
    for finding in summary["findings"]:
        lines.append(f"- **{finding['kind']}/{finding['key']}** = `{finding['value']}` — {finding['detail']}")
    lines.append("")
    lines.append("## Grafana")
    lines.append("")
    workload = (summary.get("config") or {}).get("workload") or "auth"
    other = "public" if workload == "auth" else "auth"
    lines.append(f"- Дашборд ЛР4 ({workload}): <http://localhost:3000/d/loadtest-lr4-{workload}>")
    lines.append(f"- Дашборд ЛР4 ({other}): <http://localhost:3000/d/loadtest-lr4-{other}>")
    lines.append("- Дашборд ЛР3 (микробенчмарк): <http://localhost:3000/d/benchmark-lr3>")
    lines.append("")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--raw", required=True)
    parser.add_argument("--samples", default="loadtest/runtime/container-samples.jsonl")
    parser.add_argument("--app-samples", default="loadtest/runtime/app-samples.jsonl")
    parser.add_argument("--metadata", default=None)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    metadata = read_metadata(args.metadata)
    records, vus_points, total_lines = read_raw(args.raw)
    print(f"[analyze] lines={total_lines} requests={len(records)} vus_points={len(vus_points)}")
    summary, phases, operations, cdf, histogram_rows, timeseries, ramp_curve, resource_phases, resource_samples = build_report(
        args, metadata, records, vus_points
    )
    report = {
        **summary,
        "cdf": cdf,
        "histogram": histogram_rows,
        "timeseries": timeseries,
        "ramp_curve": ramp_curve,
        "resource_phases": resource_phases,
        "resource_samples": resource_samples,
    }
    report_path = os.path.join(args.out, "report.json")
    with open(report_path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
    resource_path = os.path.join(args.out, "resource-summary.json")
    with open(resource_path, "w", encoding="utf-8") as fh:
        json.dump({
            "run_id": args.run_id,
            "scenario": args.scenario,
            "components": summary["resources"]["components"],
            "phases": resource_phases,
            "all": summary["resources"]["all"],
        }, fh, ensure_ascii=False, indent=2)
    write_markdown(summary, os.path.join(args.out, "report.md"))
    print(f"[analyze] report: {report_path}")
    print(f"[analyze] resources: {resource_path}")
    print(f"[analyze] markdown: {os.path.join(args.out, 'report.md')}")


if __name__ == "__main__":
    main()
