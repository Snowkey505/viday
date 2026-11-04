#!/usr/bin/env bash
# =============================================================================
# ЛР3 — импорт результатов микробенчмарка (report.json) в PostgreSQL для Grafana.
#
# Требует поднятого стенда:  make grafana-up
#
# Использование:
#   ./scripts/import-benchmark-to-db.sh [report.json] [--run-id ID]
#   RUN_ID=my-run ./scripts/import-benchmark-to-db.sh
#
# По умолчанию берётся benchmark/build/reports/benchmark/latest/report.json
# (run-id = имя каталога отчёта).
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

REPORT="${1:-benchmark/build/reports/benchmark/latest/report.json}"
RUN_ID="${RUN_ID:-$(basename "$(dirname "$REPORT")")}"

if ! docker inspect viday_bench_postgres >/dev/null 2>&1; then
  echo "==> [LR3] контейнер viday_bench_postgres не запущен; поднимите стенд: make grafana-up" >&2
  exit 1
fi
if [ ! -f "$REPORT" ]; then
  echo "==> [LR3] не найден отчёт: $REPORT" >&2
  exit 1
fi

echo "==> [LR3] импорт $REPORT (run-id: $RUN_ID) в PostgreSQL для Grafana"
python3 scripts/import-benchmark-to-db.py "$REPORT" --run-id "$RUN_ID" --sql /tmp/benchmark_import.sql
docker exec -i viday_bench_postgres psql -U benchmark_user -d benchmark -v ON_ERROR_STOP=1 < /tmp/benchmark_import.sql
echo "==> [LR3] готово. Дашборд: http://localhost:3000/d/benchmark-lr3 (Grafana, admin/admin)"