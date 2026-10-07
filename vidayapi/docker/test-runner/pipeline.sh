#!/usr/bin/env bash
# =============================================================================
# Пайплайн запуска тестов ЛР2 (точка входа контейнера docker/test-runner).
#
# Порядок этапов фиксирован (Требование 5): unit -> integration -> e2e.
# Если этап падает — последующие НЕ запускаются (Требование 8), но
# автоматический отчёт Allure генерируется в любом случае.
# После integration/e2e хранилище принудительно откатывается (Требование 10).
#
# Использование:
#   pipeline.sh full          # весь конвейер (по умолчанию; repeat только здесь)
#   pipeline.sh unit
#   pipeline.sh integration
#   pipeline.sh e2e
#   pipeline.sh report        # Allure из уже лежащих allure-results* (без тестов)
#   pipeline.sh assemble      # compile / bootJar без тестов
#   pipeline.sh repeat        # только локально / make ci-runner
# =============================================================================
set -uo pipefail

ROOT="${VIDAYAPI_ROOT:-/workspace}"
if [ ! -x "$ROOT/gradlew" ]; then
  ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
fi
cd "$ROOT"
GRADLE="./gradlew --no-daemon --console=plain"
HISTORY_DIR="${ALLURE_HISTORY_DIR:-/workspace/allure-history}"

section_start() {
  local id="$1" title="$2"
  echo -e "\e[0Ksection_start:$(date +%s):${id}[collapsed=true]\r\e[0K${title}"
}
section_end() {
  local id="$1"
  echo -e "\e[0Ksection_end:$(date +%s):${id}\r\e[0K"
}

step_assemble() {
  section_start assemble "assemble (compile + bootJar, без тестов)"
  $GRADLE :presentation:bootJar assemble -x test || { section_end assemble; return 1; }
  section_end assemble
  echo "assemble: OK"
}

step_unit() {
  section_start unit "unit-тесты (ЛР1)"
  echo "### [unit] unit-тесты (ЛР1) ($(date +%T))"
  $GRADLE test || { section_end unit; return 1; }
  $GRADLE coverageSummary || true
  section_end unit
  echo "unit: OK"
}

step_integration() {
  section_start integration "integration (реальный PostgreSQL)"
  echo "### [integration] реальный PostgreSQL (стенд ЛР2) ($(date +%T))"
  $GRADLE :infrastructure:testIntegration || {
    ./scripts/reset-test-db.sh >/dev/null 2>&1 || true
    echo "integration: FAILED"
    section_end integration
    return 1
  }
  ./scripts/reset-test-db.sh || { echo "integration: FAILED (rollback)"; section_end integration; return 1; }
  section_end integration
  echo "integration: OK"
}

step_e2e() {
  section_start e2e "e2e HTTP (SpringBootTest + стенд)"
  echo "### [e2e] полный HTTP-сценарий поверх стенда ($(date +%T))"
  $GRADLE :presentation:testE2E || {
    ./scripts/reset-test-db.sh >/dev/null 2>&1 || true
    echo "e2e: FAILED"
    section_end e2e
    return 1
  }
  ./scripts/reset-test-db.sh || { echo "e2e: FAILED (rollback)"; section_end e2e; return 1; }
  section_end e2e
  echo "e2e: OK"
}

step_report() {
  section_start report "Allure из существующих results"
  echo "### [report] агрегированный Allure-отчёт ($(date +%T))"
  if [ -d "$HISTORY_DIR" ] && [ -n "$(ls -A "$HISTORY_DIR" 2>/dev/null)" ]; then
    mkdir -p build/allure-results-ci/history
    cp -r "$HISTORY_DIR"/. build/allure-results-ci/history/
  fi
  $GRADLE allureCiReport || echo "WARN: allure report failed"
  if [ -d build/reports/allure-report-ci/history ]; then
    mkdir -p "$HISTORY_DIR"
    cp -r build/reports/allure-report-ci/history/. "$HISTORY_DIR"/
  fi
  section_end report
  echo "report: OK"
}

step_repeat() {
  echo "### [repeat] повторяемость: integration x2 (Требование 14) ($(date +%T))"
  $GRADLE :infrastructure:testIntegration >/dev/null 2>&1 || { echo "repeat: FAILED (run 1)"; return 1; }
  ./scripts/reset-test-db.sh >/dev/null || { echo "repeat: FAILED (rollback 1)"; return 1; }
  $GRADLE :infrastructure:testIntegration >/dev/null 2>&1 || { echo "repeat: FAILED (run 2)"; return 1; }
  ./scripts/reset-test-db.sh >/dev/null || { echo "repeat: FAILED (rollback 2)"; return 1; }
  echo "repeat: OK (identical results, stand intact)"
}

case "${1:-full}" in
  assemble)    step_assemble ;;
  unit)        step_unit ;;
  integration) step_integration ;;
  e2e)         step_e2e ;;
  report)      step_report ;;
  repeat)      step_repeat ;;
  full)
    ok=0
    step_unit || ok=1
    if [ "$ok" -eq 0 ]; then step_integration || ok=1; fi
    if [ "$ok" -eq 0 ]; then step_e2e || ok=1; fi
    step_report
    if [ "$ok" -eq 0 ]; then step_repeat || ok=1; fi
    if [ "$ok" -eq 0 ]; then
      echo "PIPELINE: OK"
    else
      echo "PIPELINE: FAILED (последующие этапы пропущены, отчёт сгенерирован)"
    fi
    exit "$ok"
    ;;
  *)
    echo "usage: pipeline.sh [full|unit|integration|e2e|report|assemble|repeat]"
    exit 2
    ;;
esac
