#!/usr/bin/env bash
# Один изолированный прогон ЛР4: чистый стенд, уникальные образы, сбор сырых
# точек, анализ и идемпотентный импорт в постоянную БД Grafana.
set -euo pipefail
cd "$(dirname "$0")/.."

SCENARIO="${SCENARIO:-smoke}"
PROFILE="${PROFILE:-$SCENARIO}"
RUN_ID="${RUN_ID:-lr4-${SCENARIO}-$(date +%Y%m%d-%H%M%S)}"
GENERATOR="${GENERATOR:-coroutines}"
WORKLOAD="${WORKLOAD:-auth}"
ERROR_RATE="${ERROR_RATE:-0}"
WARMUP="${WARMUP:-45s}"
KEEP_STACK="${KEEP_STACK:-0}"
LT_MONITORING="${LT_MONITORING:-1}"

APP_CPUS="${APP_CPUS:-${CPU:-2}}"
APP_RAM_MB="${APP_RAM_MB:-${RAM:-1536}}"
APP_HEAP_MB="${APP_HEAP_MB:-$((APP_RAM_MB / 2))}"
APP_XMS_MB="${APP_XMS_MB:-$((APP_HEAP_MB / 2))}"
COROUTINES="${COROUTINES:-450}"
DISPATCHER_THREADS="${DISPATCHER_THREADS:-${THREADS:-4}}"

SOAK_VUS="${SOAK_VUS:-60}"
SOAK_DURATION="${SOAK_DURATION:-20m}"
SMOKE_VUS="${SMOKE_VUS:-2}"
SMOKE_DURATION="${SMOKE_DURATION:-35s}"
RAMP_MAX_VUS="${RAMP_MAX_VUS:-450}"
RAMP_STEP_DURATION="${RAMP_STEP_DURATION:-1m}"
RAMP_PLATEAU_DURATION="${RAMP_PLATEAU_DURATION:-2m}"
STRESS_BASELINE_VUS="${STRESS_BASELINE_VUS:-50}"
STRESS_OVERLOAD_VUS="${STRESS_OVERLOAD_VUS:-400}"
STRESS_BASELINE_DURATION="${STRESS_BASELINE_DURATION:-3m}"
STRESS_OVERLOAD_DURATION="${STRESS_OVERLOAD_DURATION:-4m}"
STRESS_RECOVERY_DURATION="${STRESS_RECOVERY_DURATION:-5m}"
THINK_MIN_MS="${THINK_MIN_MS:-1000}"
THINK_MAX_MS="${THINK_MAX_MS:-2500}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-30s}"

EARLY_STOP="${EARLY_STOP:-1}"
EARLY_STOP_AFTER="${EARLY_STOP_AFTER:-30s}"
FAILURE_WINDOW="${FAILURE_WINDOW:-5s}"
FAILURE_THRESHOLD="${FAILURE_THRESHOLD:-0.95}"
FAILURE_WINDOWS="${FAILURE_WINDOWS:-3}"
FAILURE_MIN_REQUESTS="${FAILURE_MIN_REQUESTS:-20}"

for number in "$APP_CPUS" "$APP_RAM_MB" "$APP_HEAP_MB" "$APP_XMS_MB" "$COROUTINES" "$DISPATCHER_THREADS"; do
  [[ "$number" =~ ^[1-9][0-9]*$ ]] || { echo "параметры CPU/RAM/COROUTINES/THREADS должны быть положительными целыми" >&2; exit 2; }
done
# 256 MiB — практический минимум для Spring Boot 4 + JVM: при 64/128 MiB
# контейнер убивается OOM ещё до healthcheck. Оставляем запас 128 MiB на
# metaspace, code cache, стеки потоков и direct buffers вне heap.
MIN_APP_RAM_MB="${MIN_APP_RAM_MB:-256}"
MIN_APP_HEAP_MB="${MIN_APP_HEAP_MB:-64}"
MIN_APP_HEADROOM_MB="${MIN_APP_HEADROOM_MB:-128}"


if [ "$APP_RAM_MB" -lt 384 ]; then
  printf '==> [LR4][WARN] RAM=%sMiB — минимальный режим; под нагрузкой возможен OOM. Для сравнения CPU лучше 512 MiB.\n' \
    "$APP_RAM_MB" >&2
fi
if [ "$GENERATOR" != "coroutines" ] && [ "$GENERATOR" != "k6" ]; then
  echo "GENERATOR должен быть coroutines или k6" >&2
  exit 2
fi
if [ "$WORKLOAD" != "auth" ] && [ "$WORKLOAD" != "public" ]; then
  echo "WORKLOAD должен быть auth или public" >&2
  exit 2
fi
if [ "$WORKLOAD" = "public" ] && [ "$GENERATOR" = "k6" ]; then
  echo "WORKLOAD=public поддерживается только GENERATOR=coroutines: k6 всегда выполняет login" >&2
  exit 2
fi
if [ "$WORKLOAD" = "public" ] && [ "$ERROR_RATE" != "0" ] && [ "$ERROR_RATE" != "0.0" ]; then
  echo "Для WORKLOAD=public ERROR_RATE должен быть 0" >&2
  exit 2
fi
if [ "$SCENARIO" = "ramp" ] && [ "$RAMP_MAX_VUS" -gt "$COROUTINES" ]; then
  warn "RAMP_MAX_VUS=$RAMP_MAX_VUS > COROUTINES=$COROUTINES: план ramp будет ограничен $COROUTINES VU (VU = корутина)"
fi

if [ -n "${LT_APP_CPUSET:-}" ]; then
  APP_CPUSET="$LT_APP_CPUSET"
else
  APP_CPUSET="$(seq -s, 2 "$((1 + APP_CPUS))")"
fi
NEXT_CPU=$((2 + APP_CPUS))
if [ "$NEXT_CPU" -lt 4 ]; then NEXT_CPU=4; fi
DB_CPUSET="${LT_DB_CPUSET:-$NEXT_CPU,$((NEXT_CPU + 1))}"
CACHE_CPUSET="${LT_REDIS_CPUSET:-$((NEXT_CPU + 2))}"
MINIO_CPUSET="${LT_MINIO_CPUSET:-$((NEXT_CPU + 2))}"
K6_CPUSET="${LT_K6_CPUSET:-$((NEXT_CPU + 3)),$((NEXT_CPU + 4))}"
PROMETHEUS_CPUSET="${PROMETHEUS_CPUSET:-$((NEXT_CPU + 5))}"
GRAFANA_CPUSET="${GRAFANA_CPUSET:-$((NEXT_CPU + 5))}"
EXPORTER_CPUSET="${EXPORTER_CPUSET:-$((NEXT_CPU + 5))}"
GRAFANA_DB_CPUSET="${GRAFANA_DB_CPUSET:-$((NEXT_CPU + 5))}"

SAFE_ID="$(printf '%s' "$RUN_ID" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9_.-' '-')"
PROJECT="viday-lt-${SAFE_ID}"
OUT_DIR="${OUT_DIR:-loadtest/results/${RUN_ID}}"
NETWORK="${LT_NETWORK:-viday_loadtest_net}"
APP_IMAGE="${LT_APP_IMAGE:-viday-loadtest-app:${SAFE_ID}}"
K6_IMAGE="${LT_K6_IMAGE:-viday-loadtest-k6:${SAFE_ID}}"
GENERATOR_IMAGE="${LT_GENERATOR_IMAGE:-viday-loadtest-coroutines:${SAFE_ID}}"
COMPOSE_FILE="docker/docker-compose.loadtest.yml"

log() { printf '==> [LR4] %s\n' "$*"; }
warn() { printf '==> [LR4][WARN] %s\n' "$*" >&2; }

compose_target() {
  LT_RUN_ID="$RUN_ID" \
  LT_WORKLOAD="$WORKLOAD" \
  LT_APP_IMAGE="$APP_IMAGE" \
  LT_APP_CPUS="$APP_CPUS" \
  LT_APP_RAM_MB="$APP_RAM_MB" \
  LT_APP_HEAP_MB="$APP_HEAP_MB" \
  LT_APP_XMS_MB="$APP_XMS_MB" \
  LT_APP_CPUSET="$APP_CPUSET" \
  LT_DB_CPUSET="$DB_CPUSET" \
  LT_REDIS_CPUSET="$CACHE_CPUSET" \
  LT_MINIO_CPUSET="$MINIO_CPUSET" \
  docker compose -p "$PROJECT" -f "$COMPOSE_FILE" "$@"
}

stop_target() {
  if [ "${STACK_STOPPED:-0}" = "1" ]; then return 0; fi
  STACK_STOPPED=1
  if [ "$KEEP_STACK" = "1" ]; then
    warn "KEEP_STACK=1: стенд $PROJECT оставлен запущенным"
    return 0
  fi
  log "остановка целевого стенда $PROJECT (том PostgreSQL удаляется)"
  compose_target down -v --remove-orphans >/dev/null 2>&1 || true
}

cleanup() {
  local rc=$?
  if [ "$rc" -ne 0 ]; then
    warn "прогон завершился с кодом $rc"
    stop_target
  fi
}
trap cleanup EXIT

log "run-id: $RUN_ID"
log "сценарий: $SCENARIO/$WORKLOAD, профиль: $PROFILE, generator: $GENERATOR, error_rate: $ERROR_RATE"
log "API: CPU=$APP_CPUS RAM=${APP_RAM_MB}MiB heap=${APP_HEAP_MB}MiB; coroutines=$COROUTINES dispatcher_threads=$DISPATCHER_THREADS"
log "CPU: API=$APP_CPUSET DB=$DB_CPUSET Redis=$CACHE_CPUSET MinIO=$MINIO_CPUSET generator=$K6_CPUSET monitoring=$PROMETHEUS_CPUSET"

command -v docker >/dev/null || { echo "docker не найден" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker daemon недоступен" >&2; exit 1; }
MEM_AVAILABLE_MB=$(awk '/MemAvailable/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)
log "доступно RAM: ${MEM_AVAILABLE_MB} MiB"
if [ "$MEM_AVAILABLE_MB" -lt "$((APP_RAM_MB + 900))" ]; then
  warn "мало свободной RAM для выбранной конфигурации"
fi

mkdir -p "$OUT_DIR" loadtest/runtime docs/lr4-results
chmod 0777 "$OUT_DIR"

if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
  docker network create "$NETWORK" >/dev/null
fi

if [ "$LT_MONITORING" = "1" ]; then
  log "подъём мониторинга (Grafana/Prometheus/exporter)"
  GRAFANA_CPUSET="$GRAFANA_CPUSET" \
  PROMETHEUS_CPUSET="$PROMETHEUS_CPUSET" \
  EXPORTER_CPUSET="$EXPORTER_CPUSET" \
  GRAFANA_DB_CPUSET="$GRAFANA_DB_CPUSET" \
  docker compose -f docker/docker-compose.grafana.yml up -d
  for _ in $(seq 1 40); do
    curl -sf http://localhost:9101/health >/dev/null 2>&1 && break
    sleep 1
  done
  curl -sf http://localhost:9101/health >/dev/null 2>&1 \
    || warn "exporter не отвечает на :9101 — ресурсы могут быть неполными"
else
  warn "LT_MONITORING=0: ресурсы и история Grafana не собираются"
fi

log "сборка образа приложения $APP_IMAGE"
docker build --network=host --build-arg RUN_ID="$RUN_ID" \
  -f docker/loadtest/app.Dockerfile -t "$APP_IMAGE" . | sed 's/^/    app| /'

if [ "$GENERATOR" = "coroutines" ]; then
  log "сборка Kotlin-генератора $GENERATOR_IMAGE"
  docker build --network=host --build-arg RUN_ID="$RUN_ID" \
    -f docker/loadtest/generator.Dockerfile -t "$GENERATOR_IMAGE" . | sed 's/^/    gen| /'
else
  log "сборка k6-генератора $K6_IMAGE"
  docker build --build-arg RUN_ID="$RUN_ID" \
    -f docker/loadtest/Dockerfile -t "$K6_IMAGE" loadtest | sed 's/^/    k6 | /'
fi

log "запуск чистого целевого стенда: $PROJECT"
compose_target up -d --no-build --remove-orphans
API_CID="$(compose_target ps -q api-lt)"
if [ -z "$API_CID" ]; then
  compose_target ps >&2 || true
  exit 1
fi

log "ожидание health API (до 300 c)"
API_READY=0
status="starting"
for _ in $(seq 1 100); do
  status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$API_CID" 2>/dev/null || echo missing)"
  if [ "$status" = "healthy" ]; then API_READY=1; break; fi
  if [ "$status" = "unhealthy" ] || [ "$status" = "exited" ]; then break; fi
  sleep 3
done
if [ "$API_READY" != "1" ]; then
  warn "API не стал healthy (status=$status); последние логи:"
  compose_target logs --tail=160 api-lt >&2 || true
  warn "состояние контейнера API:"
  docker inspect -f 'status={{.State.Status}} exit={{.State.ExitCode}} oom_killed={{.State.OOMKilled}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} memory_limit={{.HostConfig.Memory}}' \
    "$API_CID" >&2 || true
  docker stats --no-stream --format 'CPU={{.CPUPerc}} MEM={{.MemUsage}}' "$API_CID" >&2 || true
  if [ "$(docker inspect -f '{{.State.OOMKilled}}' "$API_CID" 2>/dev/null || echo false)" = "true" ]; then
    warn "контейнер убит OOM: увеличьте RAM (сейчас ${APP_RAM_MB} MiB) или уменьшите APP_HEAP_MB (${APP_HEAP_MB} MiB)."
    warn "минимум для Spring Boot: RAM≥256 MiB и ≥128 MiB вне heap; практично RAM=512 MiB, heap=256 MiB."
  fi
  exit 1
fi
sleep 8

capture_application() {
  local stage="$1"
  if [ "$LT_MONITORING" != "1" ]; then return 0; fi
  for _ in $(seq 1 4); do
    if curl -sf --max-time 30 http://localhost:9101/collect-application >/dev/null; then
      log "Actuator-снимок сохранён: $stage"
      return 0
    fi
    sleep 2
  done
  return 1
}

if ! capture_application "до нагрузки"; then
  echo "не удалось получить начальный Actuator-снимок; прогон остановлен до нагрузки" >&2
  exit 1
fi

run_generator() {
  local run_id="$1" raw_file="$2" summary_file="$3" warmup="$4"
  local -a common=(
    --rm --cpuset-cpus="$K6_CPUSET" --cpus=2 --memory=1g
    --network "$NETWORK"
    --network-alias generator-lt
    --label "com.viday.loadtest.run_id=$RUN_ID"
    --label "com.viday.loadtest.component=generator"
    --label "com.viday.loadtest.workload=$WORKLOAD"
    --label "com.viday.loadtest.api_cpus=$APP_CPUS"
    --label "com.viday.loadtest.api_ram_mb=$APP_RAM_MB"
    -e "SCENARIO=$SCENARIO" -e "PROFILE=$PROFILE" -e "RUN_ID=$run_id"
    -e "BASE_URL=http://api-lt:8080" -e "ERROR_RATE=$ERROR_RATE" -e "WORKLOAD=$WORKLOAD"
    -e "API_CPUS=$APP_CPUS" -e "API_RAM_MB=$APP_RAM_MB"
    -e "METRICS_PORT=${METRICS_PORT:-9102}"
    -e "COROUTINES=$COROUTINES" -e "DISPATCHER_THREADS=$DISPATCHER_THREADS"
    -e "SOAK_VUS=$SOAK_VUS" -e "SOAK_DURATION=$SOAK_DURATION"
    -e "SMOKE_VUS=$SMOKE_VUS" -e "SMOKE_DURATION=$SMOKE_DURATION"
    -e "RAMP_MAX_VUS=$RAMP_MAX_VUS" -e "RAMP_STEP_DURATION=$RAMP_STEP_DURATION"
    -e "RAMP_PLATEAU_DURATION=$RAMP_PLATEAU_DURATION"
    -e "STRESS_BASELINE_VUS=$STRESS_BASELINE_VUS" -e "STRESS_OVERLOAD_VUS=$STRESS_OVERLOAD_VUS"
    -e "STRESS_BASELINE_DURATION=$STRESS_BASELINE_DURATION"
    -e "STRESS_OVERLOAD_DURATION=$STRESS_OVERLOAD_DURATION"
    -e "STRESS_RECOVERY_DURATION=$STRESS_RECOVERY_DURATION"
    -e "THINK_MIN_MS=$THINK_MIN_MS" -e "THINK_MAX_MS=$THINK_MAX_MS"
    -e "REQUEST_TIMEOUT=$REQUEST_TIMEOUT" -e "EARLY_STOP=$EARLY_STOP"
    -e "EARLY_STOP_AFTER=$EARLY_STOP_AFTER" -e "FAILURE_WINDOW=$FAILURE_WINDOW"
    -e "FAILURE_THRESHOLD=$FAILURE_THRESHOLD" -e "FAILURE_WINDOWS=$FAILURE_WINDOWS"
    -e "FAILURE_MIN_REQUESTS=$FAILURE_MIN_REQUESTS"
    -v "$PWD/$OUT_DIR:/results"
  )
  if [ "$warmup" = "1" ]; then
    common+=( -e "SCENARIO=smoke" -e "PROFILE=warmup" -e "SMOKE_VUS=4" -e "SMOKE_DURATION=$WARMUP" -e "ERROR_RATE=0" -e "EARLY_STOP=0" )
  fi
  if [ "$GENERATOR" = "coroutines" ]; then
    docker run --name "viday-lt-gen-${SAFE_ID:0:40}" "${common[@]}" \
      -e "OUT_DIR=/results" "$GENERATOR_IMAGE"
  else
    docker run --name "viday-lt-k6-${SAFE_ID:0:40}" "${common[@]}" \
      "$K6_IMAGE" run /scripts/scenario.js --out "json=/results/$raw_file" \
      --summary-export "/results/$summary_file" --no-color
  fi
}

if [ "$WARMUP" != "0" ] && [ "$WARMUP" != "0s" ]; then
  log "warmup: $WARMUP (4 VU, не входит в отчёт)"
  if [ "$GENERATOR" = "coroutines" ]; then
    docker run --rm --name "viday-lt-warmup-${SAFE_ID:0:40}" \
      --cpuset-cpus="$K6_CPUSET" --cpus=2 --memory=512m --network "$NETWORK" \
      --network-alias generator-lt \
      --label "com.viday.loadtest.run_id=$RUN_ID" --label "com.viday.loadtest.component=generator" \
      --label "com.viday.loadtest.workload=$WORKLOAD" \
      --label "com.viday.loadtest.api_cpus=$APP_CPUS" \
      --label "com.viday.loadtest.api_ram_mb=$APP_RAM_MB" \
      -e SCENARIO=smoke -e PROFILE=warmup -e RUN_ID="${RUN_ID}-warmup" \
      -e BASE_URL=http://api-lt:8080 -e WORKLOAD="$WORKLOAD" -e ERROR_RATE=0 \
      -e API_CPUS="$APP_CPUS" -e API_RAM_MB="$APP_RAM_MB" -e METRICS_PORT="${METRICS_PORT:-9102}" \
      -e SMOKE_VUS=4 -e SMOKE_DURATION="$WARMUP" \
      -e COROUTINES=4 -e DISPATCHER_THREADS=2 -e EARLY_STOP=0 \
      -e THINK_MIN_MS="$THINK_MIN_MS" -e THINK_MAX_MS="$THINK_MAX_MS" \
      -e REQUEST_TIMEOUT="$REQUEST_TIMEOUT" -e OUT_DIR=/results \
      -v "$PWD/$OUT_DIR:/results" "$GENERATOR_IMAGE" >/dev/null 2>&1 \
      || warn "warmup завершился с ошибкой (продолжаем)"
    [ ! -f "$OUT_DIR/raw.json" ] || mv "$OUT_DIR/raw.json" "$OUT_DIR/warmup.json"
  else
    run_generator "${RUN_ID}-warmup" warmup.json summary.json 1 >/dev/null 2>&1 \
      || warn "warmup завершился с ошибкой (продолжаем)"
  fi
fi

STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "старт генератора: $STARTED_AT"
set +e
run_generator "$RUN_ID" raw.json summary.json 0
K6_RC=$?
set -e
FINISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "генератор завершён: rc=$K6_RC, $FINISHED_AT"
capture_application "после нагрузки" || warn "финальный Actuator-снимок недоступен; начальный сохранён"

sleep 5
stop_target
if [ ! -s "$OUT_DIR/raw.json" ]; then
  echo "генератор не создал raw.json — анализировать нечего" >&2
  exit 1
fi

CPU_MODEL="$(awk -F': ' '/model name/ {print $2; exit}' /proc/cpuinfo 2>/dev/null || echo unknown)"
KERNEL="$(uname -sr)"
DOCKER_VERSION="$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo unknown)"
GIT_COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
GIT_DIRTY="$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
IMAGE_ID="$(docker image inspect -f '{{.Id}}' "$APP_IMAGE" 2>/dev/null || echo unknown)"
if [ "$GENERATOR" = "coroutines" ]; then
  GENERATOR_IMAGE_ID="$(docker image inspect -f '{{.Id}}' "$GENERATOR_IMAGE" 2>/dev/null || echo unknown)"
else
  GENERATOR_IMAGE_ID="$(docker image inspect -f '{{.Id}}' "$K6_IMAGE" 2>/dev/null || echo unknown)"
fi

RUN_ID="$RUN_ID" SCENARIO="$SCENARIO" PROFILE="$PROFILE" GENERATOR="$GENERATOR" WORKLOAD="$WORKLOAD" \
ERROR_RATE="$ERROR_RATE" SOAK_VUS="$SOAK_VUS" SOAK_DURATION="$SOAK_DURATION" \
SMOKE_VUS="$SMOKE_VUS" SMOKE_DURATION="$SMOKE_DURATION" RAMP_MAX_VUS="$RAMP_MAX_VUS" \
RAMP_STEP_DURATION="$RAMP_STEP_DURATION" RAMP_PLATEAU_DURATION="$RAMP_PLATEAU_DURATION" \
STRESS_BASELINE_VUS="$STRESS_BASELINE_VUS" STRESS_OVERLOAD_VUS="$STRESS_OVERLOAD_VUS" \
STRESS_BASELINE_DURATION="$STRESS_BASELINE_DURATION" STRESS_OVERLOAD_DURATION="$STRESS_OVERLOAD_DURATION" \
STRESS_RECOVERY_DURATION="$STRESS_RECOVERY_DURATION" THINK_MIN_MS="$THINK_MIN_MS" \
THINK_MAX_MS="$THINK_MAX_MS" REQUEST_TIMEOUT="$REQUEST_TIMEOUT" WARMUP="$WARMUP" \
COROUTINES="$COROUTINES" DISPATCHER_THREADS="$DISPATCHER_THREADS" EARLY_STOP="$EARLY_STOP" \
EARLY_STOP_AFTER="$EARLY_STOP_AFTER" FAILURE_WINDOW="$FAILURE_WINDOW" FAILURE_THRESHOLD="$FAILURE_THRESHOLD" \
FAILURE_WINDOWS="$FAILURE_WINDOWS" FAILURE_MIN_REQUESTS="$FAILURE_MIN_REQUESTS" \
STARTED_AT="$STARTED_AT" FINISHED_AT="$FINISHED_AT" K6_RC="$K6_RC" OUT_DIR="$OUT_DIR" \
APP_IMAGE="$APP_IMAGE" GENERATOR_IMAGE="$GENERATOR_IMAGE" K6_IMAGE="$K6_IMAGE" \
IMAGE_ID="$IMAGE_ID" GENERATOR_IMAGE_ID="$GENERATOR_IMAGE_ID" \
APP_CPUS="$APP_CPUS" APP_RAM_MB="$APP_RAM_MB" APP_HEAP_MB="$APP_HEAP_MB" APP_XMS_MB="$APP_XMS_MB" \
APP_CPUSET="$APP_CPUSET" DB_CPUSET="$DB_CPUSET" CACHE_CPUSET="$CACHE_CPUSET" \
MINIO_CPUSET="$MINIO_CPUSET" K6_CPUSET="$K6_CPUSET" GRAFANA_CPUSET="$GRAFANA_CPUSET" \
PROMETHEUS_CPUSET="$PROMETHEUS_CPUSET" EXPORTER_CPUSET="$EXPORTER_CPUSET" \
MEM_AVAILABLE_MB="$MEM_AVAILABLE_MB" CPU_MODEL="$CPU_MODEL" KERNEL="$KERNEL" \
DOCKER_VERSION="$DOCKER_VERSION" GIT_COMMIT="$GIT_COMMIT" GIT_DIRTY="$GIT_DIRTY" \
python3 - "$OUT_DIR/metadata.json" <<'PY'
import json
import os
import sys

env = os.environ
state = {}
try:
    with open(os.path.join(env["OUT_DIR"], "run-state.json"), encoding="utf-8") as fh:
        state = json.load(fh)
except (OSError, ValueError):
    pass
config = {
    "generator": env["GENERATOR"], "workload": env["WORKLOAD"],
    "scenario": env["SCENARIO"], "profile": env["PROFILE"],
    "api_cpus": int(env["APP_CPUS"]), "api_ram_mb": int(env["APP_RAM_MB"]),
    "api_heap_mb": int(env["APP_HEAP_MB"]), "api_xms_mb": int(env["APP_XMS_MB"]),
    "coroutines": int(env["COROUTINES"]), "dispatcher_threads": int(env["DISPATCHER_THREADS"]),
    "error_rate": float(env["ERROR_RATE"]),
    "soak_vus": int(env["SOAK_VUS"]), "soak_duration": env["SOAK_DURATION"],
    "smoke_vus": int(env["SMOKE_VUS"]), "smoke_duration": env["SMOKE_DURATION"],
    "ramp_max_vus": int(env["RAMP_MAX_VUS"]), "ramp_step_duration": env["RAMP_STEP_DURATION"],
    "ramp_plateau_duration": env["RAMP_PLATEAU_DURATION"],
    "stress_baseline_vus": int(env["STRESS_BASELINE_VUS"]),
    "stress_overload_vus": int(env["STRESS_OVERLOAD_VUS"]),
    "stress_baseline_duration": env["STRESS_BASELINE_DURATION"],
    "stress_overload_duration": env["STRESS_OVERLOAD_DURATION"],
    "stress_recovery_duration": env["STRESS_RECOVERY_DURATION"],
    "think_time_ms": [int(env["THINK_MIN_MS"]), int(env["THINK_MAX_MS"])],
    "request_timeout": env["REQUEST_TIMEOUT"], "warmup": env["WARMUP"],
    "early_stop": env["EARLY_STOP"] == "1", "early_stop_after": env["EARLY_STOP_AFTER"],
    "failure_window": env["FAILURE_WINDOW"], "failure_threshold": float(env["FAILURE_THRESHOLD"]),
    "failure_windows": int(env["FAILURE_WINDOWS"]),
    "failure_min_requests": int(env["FAILURE_MIN_REQUESTS"]),
    "early_stopped": bool(state.get("early_stopped", False)),
    "termination_reason": state.get("termination_reason", "planned duration reached"),
    "operations_per_session": 3 if env["WORKLOAD"] == "public" else 10,
    "password_seed_users": 0 if env["WORKLOAD"] == "public" else 200,
    "seed": "20 creators + 200 users + 1000 videos + 100 playlists",
}
environment = {
    "cpu_model": env["CPU_MODEL"], "kernel": env["KERNEL"], "docker_version": env["DOCKER_VERSION"],
    "cpusets": {"api": env["APP_CPUSET"], "postgres": env["DB_CPUSET"], "redis": env["CACHE_CPUSET"],
                "minio": env["MINIO_CPUSET"], "generator": env["K6_CPUSET"],
                "prometheus": env["PROMETHEUS_CPUSET"], "grafana": env["GRAFANA_CPUSET"],
                "exporter": env["EXPORTER_CPUSET"]},
    "images": {"app": env["APP_IMAGE"], "app_id": env["IMAGE_ID"],
               "generator": env["GENERATOR_IMAGE"] if env["GENERATOR"] == "coroutines" else env["K6_IMAGE"],
               "generator_id": env["GENERATOR_IMAGE_ID"]},
    "git_commit": env["GIT_COMMIT"], "git_dirty_files": int(env["GIT_DIRTY"]),
    "host_mem_available_mb_at_start": int(env["MEM_AVAILABLE_MB"]),
}
meta = {
    "run_id": env["RUN_ID"],
    "title": f"LR4 {env['WORKLOAD']} {env['PROFILE']} CPU={env['APP_CPUS']} RAM={env['APP_RAM_MB']}MiB coroutines={env['COROUTINES']}",
    "scenario": env["SCENARIO"], "started_at": env["STARTED_AT"], "finished_at": env["FINISHED_AT"],
    "generator_exit_code": int(env["K6_RC"]), "config": config, "environment": environment,
}
with open(sys.argv[1], "w", encoding="utf-8") as fh:
    json.dump(meta, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
PY

log "анализ сырых данных"
python3 scripts/analyze-loadtest.py --run-id "$RUN_ID" --scenario "$SCENARIO" \
  --raw "$OUT_DIR/raw.json" --samples loadtest/runtime/container-samples.jsonl \
  --app-samples loadtest/runtime/app-samples.jsonl --metadata "$OUT_DIR/metadata.json" --out "$OUT_DIR"

DEST="docs/lr4-results/$RUN_ID"
mkdir -p "$DEST"
cp "$OUT_DIR/report.json" "$OUT_DIR/report.md" "$OUT_DIR/resource-summary.json" "$OUT_DIR/metadata.json" "$DEST/"
[ -f "$OUT_DIR/summary.json" ] && cp "$OUT_DIR/summary.json" "$DEST/" || true
[ -f "$OUT_DIR/run-state.json" ] && cp "$OUT_DIR/run-state.json" "$DEST/" || true
ln -sfn "$RUN_ID" loadtest/results/latest
ln -sfn "$RUN_ID" docs/lr4-results/latest
log "отчёты: $OUT_DIR и $DEST"

if docker inspect viday_bench_postgres >/dev/null 2>&1; then
  log "импорт в PostgreSQL Grafana"
  if ./scripts/import-loadtest-to-db.sh "$OUT_DIR/report.json"; then
    log "дашборд: http://localhost:3000/d/loadtest-lr4-$WORKLOAD"
  else
    warn "импорт в Grafana не удался (отчёт сохранён на диске)"
  fi
else
  warn "viday_bench_postgres не запущен — импорт пропущен (make grafana-up)"
fi

if [ "$K6_RC" -ne 0 ]; then warn "генератор вернул код $K6_RC"; fi
log "готово: $RUN_ID"
[ ! -f "$OUT_DIR/report.md" ] || sed -n '1,35p' "$OUT_DIR/report.md"
