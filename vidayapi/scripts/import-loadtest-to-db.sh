#!/usr/bin/env bash
# =============================================================================
# ЛР4 — импорт report.json нагрузочного прогона в PostgreSQL Grafana.
#
# Использование:
#   ./scripts/import-loadtest-to-db.sh loadtest/results/<run-id>/report.json
#   RUN_ID=<run-id> ./scripts/import-loadtest-to-db.sh
#
# По умолчанию берётся последний прогон из loadtest/results/latest/report.json
# (симлинк latest обновляет scripts/run-loadtest.sh).
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

REPORT="${1:-loadtest/results/latest/report.json}"
RUN_ID="${RUN_ID:-$(basename "$(dirname "$REPORT")")}"

if ! docker inspect viday_bench_postgres >/dev/null 2>&1; then
  echo "==> [LR4] контейнер viday_bench_postgres не запущен; поднимите стенд: make grafana-up" >&2
  exit 1
fi
if [ ! -f "$REPORT" ]; then
  echo "==> [LR4] не найден отчёт: $REPORT" >&2
  exit 1
fi

echo "==> [LR4] импорт $REPORT (run-id: $RUN_ID) в PostgreSQL Grafana"
python3 scripts/import-loadtest-to-db.py "$REPORT" \
  --run-id "$RUN_ID" \
  --report-path "$REPORT" \
  --sql /tmp/loadtest_import.sql
docker exec -i viday_bench_postgres psql -U benchmark_user -d benchmark -v ON_ERROR_STOP=1 \
  < /tmp/loadtest_import.sql

# CDF/гистограмма по времени: без этого панели распределений не подчиняются
# таймпикеру. Используется уже сохранённый raw.json, тесты не перезапускаются.
if [ -f "loadtest/results/$RUN_ID/raw.json" ]; then
  echo "==> [LR4] CDF/гистограмма во времени для $RUN_ID"
  python3 scripts/backfill-loadtest-distributions.py \
    --only "$RUN_ID" --max-size-mb 0 \
    || echo "==> [LR4] не удалось построить распределения по времени (не критично)" >&2
fi

# Доска выбирается по workload из config прогона: auth и public не смешиваются.
WORKLOAD="$(python3 -c 'import json,sys; print((json.load(open(sys.argv[1])).get("config") or {}).get("workload") or "auth")' "$REPORT")"
echo "==> [LR4] готово. Дашборд ($WORKLOAD): http://localhost:3000/d/loadtest-lr4-$WORKLOAD (Grafana, admin/admin)"
