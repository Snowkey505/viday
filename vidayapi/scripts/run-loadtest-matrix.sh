#!/usr/bin/env bash
# Декартово произведение CPU × RAM × COROUTINES; каждая конфигурация — отдельный
# прогон, поэтому Grafana сохраняет полную историю без перезаписи результатов.
set -euo pipefail
cd "$(dirname "$0")/.."

CPUS="${CPUS:-1 2}"
RAMS="${RAMS:-512 1024}"
COROUTINES="${COROUTINES:-100}"
SCENARIO="${SCENARIO:-ramp}"
WORKLOAD="${WORKLOAD:-auth}"
RUN_PREFIX="${RUN_PREFIX:-lr4-matrix-$(date +%Y%m%d-%H%M%S)}"

for cpu in $CPUS; do
  for ram in $RAMS; do
    for coroutines in $COROUTINES; do
      run_id="${RUN_PREFIX}-c${cpu}-r${ram}-co${coroutines}"
      printf '\n========== [LR4] %s ==========\n' "$run_id"
      RUN_ID="$run_id" \
      PROFILE="c${cpu}-r${ram}-co${coroutines}" \
      SCENARIO="$SCENARIO" \
      WORKLOAD="$WORKLOAD" \
      APP_CPUS="$cpu" \
      APP_RAM_MB="$ram" \
      COROUTINES="$coroutines" \
      ./scripts/run-loadtest.sh
    done
  done
done

printf '\nМатрица завершена: %s\n' "$RUN_PREFIX"
printf 'Grafana (%s): http://localhost:3000/d/loadtest-lr4-%s\n' "$WORKLOAD" "$WORKLOAD"
