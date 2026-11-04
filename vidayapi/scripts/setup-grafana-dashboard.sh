#!/usr/bin/env bash
# =============================================================================
# ЛР3 — импорт дашборда Grafana через API (идемпотентно).
#
# Провижининг дашбордов Grafana 11 через файлы работает ненадёжно, поэтому
# дашборд импортируется через официальный API /api/dashboards/import.
# Источник данных (PostgreSQL) провижинится штатно (datasources/*.yml).
#
# Использование:  ./scripts/setup-grafana-dashboard.sh
# Переменные:     GRAFANA_URL (по умолчанию http://localhost:3000),
#                 GRAFANA_USER / GRAFANA_PASSWORD (по умолчанию admin/admin)
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
GRAFANA_USER="${GRAFANA_USER:-admin}"
GRAFANA_PASSWORD="${GRAFANA_PASSWORD:-admin}"
DASHBOARD_FILE="docker/grafana/provisioning/dashboards/benchmark.json"

echo "==> [LR3] ожидание Grafana ($GRAFANA_URL)..."
ready=0
for i in $(seq 1 60); do
  if curl -sf -u "$GRAFANA_USER:$GRAFANA_PASSWORD" "$GRAFANA_URL/api/health" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
if [ "$ready" != "1" ]; then
  echo "==> [LR3] Grafana не поднялась за 120 c. Поднимите стенд: make grafana-up" >&2
  exit 1
fi

# Удаляем старую версию дашборда (если есть) — иначе API вернёт version-mismatch.
curl -sf -X DELETE -u "$GRAFANA_USER:$GRAFANA_PASSWORD" \
  "$GRAFANA_URL/api/dashboards/uid/benchmark-lr3" >/dev/null 2>&1 || true

echo "==> [LR3] импорт дашборда: $DASHBOARD_FILE"
resp="$(curl -sS -w '\n%{http_code}' -X POST -u "$GRAFANA_USER:$GRAFANA_PASSWORD" \
  -H "Content-Type: application/json" \
  --data-binary @"$DASHBOARD_FILE" \
  "$GRAFANA_URL/api/dashboards/import")"
code="$(printf '%s\n' "$resp" | tail -n1)"
body="$(printf '%s\n' "$resp" | sed '$d')"
if [ "$code" != "200" ] && [ "$code" != "201" ]; then
  echo "==> [LR3] импорт дашборда не удался (HTTP $code): $body" >&2
  exit 1
fi
echo "==> [LR3] дашборд готов: $GRAFANA_URL/d/benchmark-lr3 (Grafana, $GRAFANA_USER/$GRAFANA_PASSWORD)"