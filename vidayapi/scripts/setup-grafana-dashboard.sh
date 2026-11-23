#!/usr/bin/env bash
# =============================================================================
# ЛР3/ЛР4 — импорт дашбордов Grafana через API (идемпотентно).
#
# Провижининг дашбордов Grafana 11 через файлы работает ненадёжно, поэтому
# дашборды импортируются через официальный API /api/dashboards/import.
# Источники данных (PostgreSQL, Prometheus) провижинятся штатно
# (datasources/*.yml).
#
# Импортируются ТРИ независимых дашборда:
#   • benchmark-lr3        — микробенчмарк ЛР3 (сохраняется без изменений);
#   • loadtest-lr4-auth    — нагрузочные прогоны WORKLOAD=auth (матрикс);
#   • loadtest-lr4-public  — нагрузочные прогоны WORKLOAD=public.
#
# Разделение по сценариям нужно, чтобы auth и public не смешивались на одной
# оси X и чтобы у каждого сценария был свой общий отчёт.
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

echo "==> ожидание Grafana ($GRAFANA_URL)..."
ready=0
for i in $(seq 1 60); do
  if curl -sf -u "$GRAFANA_USER:$GRAFANA_PASSWORD" "$GRAFANA_URL/api/health" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
if [ "$ready" != "1" ]; then
  echo "==> Grafana не поднялась за 120 c. Поднимите стенд: make grafana-up" >&2
  exit 1
fi

import_dashboard() {
  local uid="$1"
  local file="$2"
  local label="$3"

  if [ ! -f "$file" ]; then
    echo "==> [$label] пропуск: нет файла $file" >&2
    return 0
  fi

  # Удаляем старую версию (если есть) — иначе API вернёт version-mismatch.
  curl -sf -X DELETE -u "$GRAFANA_USER:$GRAFANA_PASSWORD" \
    "$GRAFANA_URL/api/dashboards/uid/$uid" >/dev/null 2>&1 || true

  echo "==> [$label] импорт дашборда: $file"
  local resp code body
  resp="$(curl -sS -w '\n%{http_code}' -X POST -u "$GRAFANA_USER:$GRAFANA_PASSWORD" \
    -H "Content-Type: application/json" \
    --data-binary @"$file" \
    "$GRAFANA_URL/api/dashboards/import")"
  code="$(printf '%s\n' "$resp" | tail -n1)"
  body="$(printf '%s\n' "$resp" | sed '$d')"
  if [ "$code" != "200" ] && [ "$code" != "201" ]; then
    echo "==> [$label] импорт не удался (HTTP $code): $body" >&2
    exit 1
  fi
  echo "==> [$label] дашборд готов: $GRAFANA_URL/d/$uid"
}

import_dashboard "benchmark-lr3" \
  "docker/grafana/provisioning/dashboards/benchmark.json" "ЛР3"
import_dashboard "loadtest-lr4-auth" \
  "docker/grafana/provisioning/dashboards/loadtest-auth.json" "ЛР4 auth"
import_dashboard "loadtest-lr4-public" \
  "docker/grafana/provisioning/dashboards/loadtest-public.json" "ЛР4 public"

# Удаляем устаревшую общую доску loadtest-lr4 (до разделения по workload),
# иначе она останется в Grafana со старыми секундами вместо таймстемпов.
curl -sf -X DELETE -u "$GRAFANA_USER:$GRAFANA_PASSWORD" \
  "$GRAFANA_URL/api/dashboards/uid/loadtest-lr4" >/dev/null 2>&1 || true

echo "==> Grafana готова ($GRAFANA_USER/$GRAFANA_PASSWORD):"
echo "    ЛР3:         $GRAFANA_URL/d/benchmark-lr3"
echo "    ЛР4 auth:    $GRAFANA_URL/d/loadtest-lr4-auth"
echo "    ЛР4 public:  $GRAFANA_URL/d/loadtest-lr4-public"
