#!/usr/bin/env python3
"""Prometheus exporter ресурсов и Actuator-снимков контейнеров ЛР4.

Источник данных — Docker Engine API (/var/run/docker.sock), только stdlib.
Docker-ресурсы и приложение сохраняются в JSONL для постоянной истории Grafana.
"""
import http.client
import json
import os
import re
import socket
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DOCKER_SOCKET = os.environ.get("DOCKER_SOCKET", "/var/run/docker.sock")
PORT = int(os.environ.get("EXPORTER_PORT", "9101"))
INTERVAL = float(os.environ.get("SAMPLE_INTERVAL", "5"))
OUT_FILE = os.environ.get("SAMPLES_FILE", "/data/container-samples.jsonl")
APP_OUT_FILE = os.environ.get("APP_SAMPLES_FILE", "/data/app-samples.jsonl")
RUN_LABEL = "com.viday.loadtest.run_id"
COMPONENT_LABEL = "com.viday.loadtest.component"
WORKLOAD_LABEL = "com.viday.loadtest.workload"
API_CPUS_LABEL = "com.viday.loadtest.api_cpus"
API_RAM_LABEL = "com.viday.loadtest.api_ram_mb"
METRIC_RE = re.compile(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+([-+0-9.eE]+)$")
LABEL_RE = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:\\.|[^"\\])*)"')


class UnixHTTPConnection(http.client.HTTPConnection):
    def __init__(self, socket_path, timeout=10):
        super().__init__("localhost", timeout=timeout)
        self.socket_path = socket_path

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self.socket_path)


def docker_get(path):
    conn = UnixHTTPConnection(DOCKER_SOCKET)
    try:
        conn.request("GET", path, headers={"Host": "docker"})
        resp = conn.getresponse()
        data = resp.read()
        if resp.status >= 400:
            raise RuntimeError(f"docker API {path}: HTTP {resp.status}: {data[:200]!r}")
        return json.loads(data.decode("utf-8"))
    finally:
        conn.close()


def escape_label(value):
    return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


def sum_blkio(stats, op):
    total = 0
    entries = (stats.get("blkio_stats") or {}).get("io_service_bytes_recursive") or []
    for entry in entries:
        if str(entry.get("op", "")).lower() == op:
            total += int(entry.get("value") or 0)
    return total


def cgroup_v2_io(container_id):
    candidates = [
        f"/sys/fs/cgroup/system.slice/docker-{container_id}.scope/io.stat",
        f"/sys/fs/cgroup/docker/{container_id}/io.stat",
        f"/sys/fs/cgroup/{container_id}/io.stat",
        f"/sys/fs/cgroup/system.slice/docker-{container_id[:12]}.scope/io.stat",
    ]
    for path in candidates:
        try:
            read_b, write_b = 0, 0
            with open(path, "r", encoding="utf-8") as fh:
                for line in fh:
                    for part in line.strip().split()[1:]:
                        if part.startswith("rbytes="):
                            read_b += int(part.split("=", 1)[1])
                        elif part.startswith("wbytes="):
                            write_b += int(part.split("=", 1)[1])
            return read_b, write_b
        except (FileNotFoundError, OSError):
            continue
    return 0, 0


def parse_memory(mem):
    usage = int((mem or {}).get("usage") or 0)
    stats = (mem or {}).get("stats") or {}
    cache = stats.get("inactive_file", stats.get("total_inactive_file", stats.get("cache", 0))) or 0
    working_set = max(0, usage - int(cache))
    limit = int((mem or {}).get("limit") or 0)
    return working_set, limit


def parse_networks(networks):
    rx = sum(int((n or {}).get("rx_bytes") or 0) for n in (networks or {}).values())
    tx = sum(int((n or {}).get("tx_bytes") or 0) for n in (networks or {}).values())
    return rx, tx


def cpuset_size(value):
    size = 0
    for part in str(value or "").split(","):
        if not part:
            continue
        if "-" in part:
            start, end = part.split("-", 1)
            size += int(end) - int(start) + 1
        else:
            size += 1
    return size


def effective_cpu_limit(host_config, online_cpus):
    host_config = host_config or {}
    limits = []
    cpuset = host_config.get("CpusetCpus")
    if cpuset:
        limits.append(float(cpuset_size(cpuset)))
    nano_cpus = int(host_config.get("NanoCPUs") or 0)
    if nano_cpus > 0:
        limits.append(nano_cpus / 1_000_000_000.0)
    quota = int(host_config.get("CpuQuota") or 0)
    period = int(host_config.get("CpuPeriod") or 0)
    if quota > 0 and period > 0:
        limits.append(quota / period)
    return min(limits) if limits else float(max(1, online_cpus))


def cpu_percent(stats, previous, cpu_limit):
    cpu = stats.get("cpu_stats") or {}
    pre = stats.get("precpu_stats") or {}
    usage = int((cpu.get("cpu_usage") or {}).get("total_usage") or 0)
    system = int(cpu.get("system_cpu_usage") or 0)
    pre_usage = int((pre.get("cpu_usage") or {}).get("total_usage") or 0)
    pre_system = int(pre.get("system_cpu_usage") or 0)
    if pre_system <= 0 or pre_usage <= 0:
        pre_usage, pre_system = previous
    online = int(cpu.get("online_cpus") or len((cpu.get("cpu_usage") or {}).get("percpu_usage") or []) or 1)
    cpu_delta = usage - pre_usage
    system_delta = system - pre_system
    if cpu_delta < 0 or system_delta <= 0:
        return 0.0, 0.0, (usage, system)
    raw = (cpu_delta / system_delta) * online * 100.0
    normalized = min(100.0, raw / max(cpu_limit, 0.01))
    return raw, normalized, (usage, system)


def parse_prometheus(text):
    metrics = []
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        match = METRIC_RE.match(line)
        if not match:
            continue
        try:
            value = float(match.group(3))
        except ValueError:
            continue
        labels = {m.group(1): m.group(2).replace('\\"', '"').replace("\\n", "\n").replace("\\\\", "\\")
                  for m in LABEL_RE.finditer(match.group(2) or "")}
        metrics.append((match.group(1), labels, value))
    return metrics


def metric_sum(metrics, name, label_filter=None):
    total = 0.0
    for metric, labels, value in metrics:
        if metric == name and (label_filter is None or label_filter(labels)):
            total += value
    return total


def actuator_metrics(cid):
    details = CONTAINER_DETAILS.get(cid)
    if details is None:
        details = docker_get(f"/containers/{cid}/json")
        CONTAINER_DETAILS[cid] = details
    networks = (details.get("NetworkSettings") or {}).get("Networks") or {}
    preferred = os.environ.get("APP_NETWORK", "viday_loadtest_net")
    ordered_networks = [networks.get(preferred)] + [net for name, net in networks.items() if name != preferred]
    address = next((net.get("IPAddress") for net in ordered_networks if net and net.get("IPAddress")), None)
    if not address:
        raise RuntimeError("API container has no IP address")
    conn = http.client.HTTPConnection(address, 8080, timeout=10)
    try:
        conn.request("GET", "/actuator/prometheus", headers={"Accept": "text/plain"})
        response = conn.getresponse()
        body = response.read().decode("utf-8")
        if response.status != 200:
            raise RuntimeError(f"Actuator HTTP {response.status}")
        return parse_prometheus(body)
    finally:
        conn.close()


def rate(current, previous, key, now):
    if previous is None:
        return 0.0
    elapsed = now - previous["time"]
    if elapsed <= 0:
        return 0.0
    return max(0.0, current[key] - previous[key]) / elapsed


def app_sample(cid, run_id, metrics, now):
    user_http = lambda labels: "actuator" not in labels.get("uri", "")
    http_total = metric_sum(metrics, "http_server_requests_seconds_count", user_http)
    http_5xx = metric_sum(
        metrics, "http_server_requests_seconds_count",
        lambda labels: user_http(labels) and labels.get("status", "").startswith("5"),
    )
    gc_total = metric_sum(metrics, "jvm_gc_pause_seconds_sum")
    rejected_total = metric_sum(metrics, "tomcat_sessions_rejected_sessions_total")
    current = {
        "time": now,
        "http_total": http_total,
        "http_5xx": http_5xx,
        "gc_total": gc_total,
        "rejected_total": rejected_total,
    }
    previous = PREV_APP.get(run_id)
    PREV_APP[run_id] = current
    return {
        "run_id": run_id,
        "threads_live": metric_sum(metrics, "jvm_threads_live_threads"),
        "threads_daemon": metric_sum(metrics, "jvm_threads_daemon_threads"),
        "threads_runnable": metric_sum(metrics, "jvm_threads_states_threads", lambda l: l.get("state") == "runnable"),
        "threads_waiting": metric_sum(metrics, "jvm_threads_states_threads", lambda l: l.get("state") == "waiting"),
        "requests_in_flight": metric_sum(metrics, "http_server_requests_active_seconds_gcount"),
        "executor_active": metric_sum(metrics, "executor_active_threads"),
        "executor_pool": metric_sum(metrics, "executor_pool_size_threads"),
        "heap_bytes": metric_sum(metrics, "jvm_memory_used_bytes", lambda l: l.get("area") == "heap"),
        "gc_pause_rate": rate(current, previous, "gc_total", now),
        "hikari_active": metric_sum(metrics, "hikaricp_connections_active"),
        "hikari_idle": metric_sum(metrics, "hikaricp_connections_idle"),
        "http_rps": rate(current, previous, "http_total", now),
        "http_5xx_rps": rate(current, previous, "http_5xx", now),
        "tomcat_active": metric_sum(metrics, "tomcat_sessions_active_current_sessions"),
        "tomcat_rejected_rate": rate(current, previous, "rejected_total", now),
    }


def collect():
    containers = docker_get("/containers/json?all=0")
    rows = []
    app_rows = []
    for item in containers:
        labels = item.get("Labels") or {}
        run_id = labels.get(RUN_LABEL)
        if not run_id:
            continue
        component = labels.get(COMPONENT_LABEL) or (item.get("Names") or ["unknown"])[0].lstrip("/")
        cid = item.get("Id") or ""
        name = (item.get("Names") or [cid[:12]])[0].lstrip("/")
        try:
            stats = docker_get(f"/containers/{cid}/stats?stream=false")
            details = CONTAINER_DETAILS.get(cid)
            if details is None:
                details = docker_get(f"/containers/{cid}/json")
                CONTAINER_DETAILS[cid] = details
            online = int((stats.get("cpu_stats") or {}).get("online_cpus") or 1)
            cpu_limit = effective_cpu_limit(details.get("HostConfig"), online)
        except Exception as exc:
            print(f"[exporter] stats failed for {name}: {exc}", flush=True)
            continue
        prev = PREV_CPU.get(cid, (0, 0))
        raw_cpu, normalized_cpu, PREV_CPU[cid] = cpu_percent(stats, prev, cpu_limit)
        mem_ws, mem_limit = parse_memory(stats.get("memory_stats") or {})
        read_b = sum_blkio(stats, "read")
        write_b = sum_blkio(stats, "write")
        if read_b == 0 and write_b == 0:
            fallback_read, fallback_write = cgroup_v2_io(cid)
            if fallback_read or fallback_write:
                read_b, write_b = fallback_read, fallback_write
        rx, tx = parse_networks(stats.get("networks") or {})
        pids = int((stats.get("pids_stats") or {}).get("current") or 0)
        rows.append({
            "run_id": run_id,
            "component": component,
            "workload": labels.get(WORKLOAD_LABEL) or "-",
            "api_cpus": labels.get(API_CPUS_LABEL) or "-",
            "api_ram_mb": labels.get(API_RAM_LABEL) or "-",
            "container": name,
            "container_id": cid,
            "cpu_percent": round(raw_cpu, 4),
            "cpu_percent_normalized": round(normalized_cpu, 4),
            "cpu_limit": cpu_limit,
            "memory_working_set_bytes": mem_ws,
            "memory_limit_bytes": mem_limit,
            "block_read_bytes": read_b,
            "block_write_bytes": write_b,
            "network_rx_bytes": rx,
            "network_tx_bytes": tx,
            "pids": pids,
        })
        if component == "api":
            try:
                app_rows.append(app_sample(cid, run_id, actuator_metrics(cid), time.time()))
            except Exception as exc:
                print(f"[exporter] Actuator failed for {name}: {exc}", flush=True)
    return rows, app_rows


def collect_application():
    rows = []
    for item in docker_get("/containers/json?all=0"):
        labels = item.get("Labels") or {}
        run_id = labels.get(RUN_LABEL)
        if not run_id or labels.get(COMPONENT_LABEL) != "api":
            continue
        cid = item.get("Id") or ""
        name = (item.get("Names") or [cid[:12]])[0].lstrip("/")
        try:
            rows.append(app_sample(cid, run_id, actuator_metrics(cid), time.time()))
        except Exception as exc:
            print(f"[exporter] Actuator failed for {name}: {exc}", flush=True)
    return rows


PREV_CPU = {}
PREV_APP = {}
CONTAINER_DETAILS = {}
APP_LOCK = threading.Lock()
LATEST_ROWS = []
LATEST_TIME = None
LOCK = threading.Lock()


def render_metrics(rows):
    lines = []
    definitions = [
        ("viday_container_cpu_percent", "gauge", "CPU utilization as a percentage of the effective container CPU limit"),
        ("viday_container_cpu_per_core_percent", "gauge", "Raw Docker CPU utilization where 100 percent means one host core"),
        ("viday_container_cpu_limit", "gauge", "Effective CPU limit from cpuset and quota"),
        ("viday_container_memory_working_set_bytes", "gauge", "Container memory working set, bytes"),
        ("viday_container_memory_limit_bytes", "gauge", "Container memory limit, bytes"),
        ("viday_container_block_read_bytes_total", "counter", "Container block device bytes read, cumulative"),
        ("viday_container_block_write_bytes_total", "counter", "Container block device bytes written, cumulative"),
        ("viday_container_network_rx_bytes_total", "counter", "Container network bytes received, cumulative"),
        ("viday_container_network_tx_bytes_total", "counter", "Container network bytes transmitted, cumulative"),
        ("viday_container_pids", "gauge", "Container process count"),
    ]
    for name, typ, help_text in definitions:
        lines.append(f"# HELP {name} {help_text}")
        lines.append(f"# TYPE {name} {typ}")
    for row in rows:
        labels = (
            f'run_id="{escape_label(row["run_id"])}",'
            f'component="{escape_label(row["component"])}",'
            f'workload="{escape_label(row.get("workload") or "-")}",'
            f'api_cpus="{escape_label(row.get("api_cpus") or "-")}",'
            f'api_ram_mb="{escape_label(row.get("api_ram_mb") or "-")}",'
            f'container="{escape_label(row["container"])}"'
        )
        values = {
            "viday_container_cpu_percent": row["cpu_percent_normalized"],
            "viday_container_cpu_per_core_percent": row["cpu_percent"],
            "viday_container_cpu_limit": row["cpu_limit"],
            "viday_container_memory_working_set_bytes": row["memory_working_set_bytes"],
            "viday_container_memory_limit_bytes": row["memory_limit_bytes"],
            "viday_container_block_read_bytes_total": row["block_read_bytes"],
            "viday_container_block_write_bytes_total": row["block_write_bytes"],
            "viday_container_network_rx_bytes_total": row["network_rx_bytes"],
            "viday_container_network_tx_bytes_total": row["network_tx_bytes"],
            "viday_container_pids": row["pids"],
        }
        for name, _typ, _help in definitions:
            lines.append(f"{name}{{{labels}}} {values[name]}")
    lines.append("# HELP viday_exporter_last_scrape_timestamp_seconds Unix time of the last successful sample")
    lines.append("# TYPE viday_exporter_last_scrape_timestamp_seconds gauge")
    lines.append(f"viday_exporter_last_scrape_timestamp_seconds {time.time():.3f}")
    return "\n".join(lines) + "\n"


def append_jsonl(path, rows, now):
    with open(path, "a", encoding="utf-8") as fh:
        for row in rows:
            record = dict(row)
            record["ts"] = now
            fh.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
        fh.flush()
    os.chmod(path, 0o666)


def append_application(rows):
    if not rows:
        return 0
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    with APP_LOCK:
        append_jsonl(APP_OUT_FILE, rows, now)
    return len(rows)


def sample_loop():
    global LATEST_ROWS, LATEST_TIME
    os.makedirs(os.path.dirname(OUT_FILE), exist_ok=True)
    while True:
        started = time.time()
        try:
            rows, app_rows = collect()
            now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
            with LOCK:
                LATEST_ROWS = rows
                LATEST_TIME = now
            append_jsonl(OUT_FILE, rows, now)
            append_application(app_rows)
        except Exception as exc:
            print(f"[exporter] sample failed: {exc}", flush=True)
        elapsed = time.time() - started
        time.sleep(max(0.5, INTERVAL - elapsed))


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/health"):
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"ok\n")
            return
        if self.path.startswith("/collect-application"):
            count = append_application(collect_application())
            body = json.dumps({"samples": count}).encode("utf-8")
            self.send_response(200 if count else 503)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path.startswith("/metrics"):
            with LOCK:
                rows = list(LATEST_ROWS)
            body = render_metrics(rows).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(b"viday-lr4 container exporter: /metrics /health /collect-application\n")

    def log_message(self, fmt, *args):
        return


if __name__ == "__main__":
    print(f"[exporter] docker={DOCKER_SOCKET} port={PORT} interval={INTERVAL}s out={OUT_FILE}", flush=True)
    threading.Thread(target=sample_loop, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
