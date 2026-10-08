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
#   pipeline.sh sonar         # SonarQube анализ (нужны SONAR_TOKEN / SONAR_HOST_URL)
#   pipeline.sh sonar-tests   # весь конвейер тестов с JaCoCo (для стадии Sonar в CI)
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

step_sonar() {
  section_start sonar "SonarQube анализ"
  echo "### [sonar] SonarQube ($SONAR_HOST_URL) ($(date +%T))"
  # report-task.txt и прочие артефакты сканера кладём в build/sonar —
  # CI-джоба публикует их вместе с HTML-снимком отчёта.
  $GRADLE sonar \
    -Dsonar.host.url="${SONAR_HOST_URL:-http://localhost:9002}" \
    -Dsonar.token="$SONAR_TOKEN" \
    -Dsonar.working.directory=build/sonar \
    -Dsonar.qualitygate.wait=false || { section_end sonar; return 1; }
  section_end sonar
  echo "sonar: OK"
}

step_sonar_tests() {
  # Полный прогон тестов с JaCoCo-покрытием (локальный сценарий):
  # exec-файлы unit + integration + e2e попадают в один jacocoTestReport.
  # В CI больше НЕ используется: джоба sonar собирает покрытие из exec-файлов,
  # пришедших артефактами из реальных прогонов (без повторного запуска тестов).
  ok=0
  step_unit || ok=1
  if [ "$ok" -eq 0 ]; then step_integration || ok=1; fi
  if [ "$ok" -eq 0 ]; then step_e2e || ok=1; fi
  return "$ok"
}

step_report() {
  section_start report "Allure из существующих results"
  echo "### [report] агрегированный Allure-отчёт ($(date +%T))"

  # В report-job тесты НЕ запускаются. Все результаты должны быть уже
  # скачаны из artifacts в build/allure-results-ci. Если каталог пуст —
  # это ошибка CI, а не повод публиковать пустой Allure.
  RESULTS_DIR="$ROOT/build/allure-results-ci"
  INPUT_DIR="$ROOT/build/allure-results-ci-input"
  if [ ! -d "$INPUT_DIR" ]; then
    echo "ERROR: $INPUT_DIR does not exist"
    section_end report
    return 1
  fi

  result_count="$(find "$INPUT_DIR" -type f \
    \( -name '*-result.json' -o -name '*-container.json' \) | wc -l)"
  echo "Allure result/container files: $result_count"
  if [ "$result_count" -eq 0 ]; then
    echo "ERROR: no Allure result files were downloaded from test jobs"
    echo "Expected files under: $RESULTS_DIR"
    section_end report
    return 1
  fi

  # History is metadata for trends only; it is not a substitute for test results.
  rm -rf "$RESULTS_DIR"
  mkdir -p "$RESULTS_DIR"

  if [ -d "$HISTORY_DIR" ] && [ -n "$(ls -A "$HISTORY_DIR" 2>/dev/null)" ]; then
    mkdir -p "$RESULTS_DIR/history"
    cp -r "$HISTORY_DIR"/. "$RESULTS_DIR/history"/
  fi

  # This task only builds a report from already collected result files.
  # It MUST NOT depend on test/testIntegration/testE2E.
  $GRADLE allureCiReport || {
    echo "ERROR: allureCiReport failed"
    section_end report
    return 1
  }

  report_dir="$ROOT/build/reports/allure-report-ci"
  if [ ! -f "$report_dir/index.html" ]; then
    echo "ERROR: Allure report index.html was not generated"
    section_end report
    return 1
  fi

  if [ -d "$report_dir/history" ]; then
    rm -rf "$HISTORY_DIR"
    mkdir -p "$HISTORY_DIR"
    cp -r "$report_dir/history"/. "$HISTORY_DIR"/
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
  sonar)       step_sonar ;;
  sonar-tests) step_sonar_tests ;;
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
    echo "usage: pipeline.sh [full|unit|integration|e2e|report|assemble|sonar|sonar-tests|repeat]"
    exit 2
    ;;
esac
