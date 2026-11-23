#!/usr/bin/env bash
# =============================================================================
# ЛР4 — последовательный запуск полной серии нагрузочных испытаний.
#
# Серия (каждый пункт = отдельный образ приложения, отдельный образ генератора,
# чистый том PostgreSQL и одинаковый warmup). По умолчанию используется собственный
# Kotlin-генератор; GENERATOR=k6 сохраняет совместимость со старой методикой.
# При серии окон с отказами >=80% прогон завершается досрочно.
#   1. smoke       — проверка сценария/сида (30 c);
#   2. ramp        — постепенный рост 0→450 VU за 12 мин (поиск зоны отказа);
#   3. stress      — baseline 50 VU → перегруз 400 VU → восстановление 50 VU;
#   4. soak        — стабильные 60 VU 20 мин (анализ ресурсов/утечек);
#   5. soak-10pct  — 15 мин, ровно 10% ошибочных операций;
#   6. soak-20pct  — 15 мин, ровно 20% ошибочных операций.
#
# Использование:
#   ./scripts/run-all-loadtests.sh
#   ONLY=smoke,ramp ./scripts/run-all-loadtests.sh
#   SOAK_DURATION=30m SOAK_VUS=80 ./scripts/run-all-loadtests.sh
#   RUN_PREFIX=lr4-final ./scripts/run-all-loadtests.sh
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

RUN_PREFIX="${RUN_PREFIX:-lr4-$(date +%Y%m%d-%H%M)}"
ONLY="${ONLY:-}"

log() { printf '\n========== [LR4] %s ==========\n' "$*"; }

should_run() {
  [ -z "$ONLY" ] || [[ ",$ONLY," == *",$1,"* ]]
}

run_one() {
  local label="$1"
  local scenario="$2"
  local error_rate="$3"
  shift 3

  if ! should_run "$label"; then
    log "пропуск $label (ONLY=$ONLY)"
    return 0
  fi

  log "$label: scenario=$scenario error_rate=$error_rate $*"
  env \
    SCENARIO="$scenario" \
    PROFILE="$label" \
    ERROR_RATE="$error_rate" \
    RUN_ID="${RUN_PREFIX}-${label}" \
    "$@" \
    ./scripts/run-loadtest.sh
}

# 1. Быстрая проверка.
run_one smoke smoke 0 \
  WARMUP=20s \
  SMOKE_VUS=2 \
  SMOKE_DURATION=35s

# 2. Постепенный рост: 10 ступеней по 1 мин + 2 мин на максимуме.
run_one ramp ramp 0 \
  RAMP_MAX_VUS="${RAMP_MAX_VUS:-450}"

# 3. Перегруз и восстановление: 3+4+5 мин.
run_one stress stress 0 \
  STRESS_BASELINE_VUS="${STRESS_BASELINE_VUS:-50}" \
  STRESS_OVERLOAD_VUS="${STRESS_OVERLOAD_VUS:-400}"

# 4. Длительная стабильная нагрузка.
run_one soak soak 0 \
  SOAK_VUS="${SOAK_VUS:-60}" \
  SOAK_DURATION="${SOAK_DURATION:-20m}"

# 5-6. Та же нагрузка с 10% и 20% ошибочных операций.
run_one soak-10pct soak-errors 0.1 \
  SOAK_VUS="${ERROR_SOAK_VUS:-${SOAK_VUS:-60}}" \
  SOAK_DURATION="${ERROR_SOAK_DURATION:-15m}"

run_one soak-20pct soak-errors 0.2 \
  SOAK_VUS="${ERROR_SOAK_VUS:-${SOAK_VUS:-60}}" \
  SOAK_DURATION="${ERROR_SOAK_DURATION:-15m}"

log "серия завершена: $RUN_PREFIX"
echo "Отчёты: loadtest/results/<run-id>/ и docs/lr4-results/<run-id>/"
echo "Grafana auth:   http://localhost:3000/d/loadtest-lr4-auth"
echo "Grafana public: http://localhost:3000/d/loadtest-lr4-public"
