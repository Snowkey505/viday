#!/usr/bin/env bash
# =============================================================================
# Захват трафика E2E-сценария средством захвата пакетов (tcpdump) — Задание 5 ЛР2.
#
# Как работает:
#   1) в контейнере приложения viday_api (network_mode: host) запускается
#      tcpdump на loopback-интерфейсе (порт 8080);
#   2) scripts/e2e-curl.sh имитирует действия E2E-сценария через curl;
#   3) tcpdump останавливается, pcap копируется в traffic/ и печатается
#      читаемый дайджест HTTP-запросов/ответов.
#
# Требует поднятый стенд + контейнер приложения:
#   docker compose -f docker/docker-compose.test.yml up -d
#   docker compose -f docker/docker-compose.app.yml up -d --build
#
# Альтернатива без Docker (приложение запущено на хосте, make run):
#   CAPTURE_MODE=host sudo ./scripts/traffic-capture.sh
# =============================================================================
set -euo pipefail

APP_CONTAINER="${VIDAY_APP_CONTAINER:-viday_api}"
OUT_DIR="traffic"
mkdir -p "$OUT_DIR"
PCAP="$OUT_DIR/viday-e2e-$(date +%Y%m%d-%H%M%S).pcap"

capture_container() {
  echo "start tcpdump in container $APP_CONTAINER (lo, tcp port 8080)"
  # pkill -x: точное совпадение имени процесса — НЕ матчим собственный sh
  # (вариант с -f "tcpdump.*..." убивал сам себя: паттерн есть в cmdline sh -c)
  docker exec "$APP_CONTAINER" sh -c 'rm -f /tmp/viday-e2e.pcap; pkill -x tcpdump 2>/dev/null || true'
  docker exec -d "$APP_CONTAINER" \
    tcpdump -i lo -s 0 -w /tmp/viday-e2e.pcap 'tcp port 8080'

  ./scripts/e2e-curl.sh || true

  sleep 1
  docker exec "$APP_CONTAINER" sh -c 'pkill -INT -x tcpdump 2>/dev/null || true'
  sleep 1
  docker cp "$APP_CONTAINER:/tmp/viday-e2e.pcap" "$PCAP"

  echo
  echo "capture saved: $PCAP ($(du -h "$PCAP" | cut -f1))"
  echo "--- HTTP digest (request lines / status lines) ---"
  # Трафик идёт по IPv6-loopback (localhost -> ::1); дайджест берём из декодированных
  # tcpdump'ом HTTP-сводок (они уже чистые), а не из -A-дампа полезной нагрузки,
  # где перед текстом идут бинарные байты TCP/IP-заголовков.
  docker exec "$APP_CONTAINER" sh -c \
    "tcpdump -r /tmp/viday-e2e.pcap 'tcp port 8080' 2>/dev/null \
      | grep -aE 'HTTP:' | sed 's/^.*HTTP:/HTTP:/' | sort -u | head -60" || true
  echo "--- total packets: $(docker exec "$APP_CONTAINER" tcpdump -r /tmp/viday-e2e.pcap 2>/dev/null | wc -l) ---"
}

capture_host() {
  echo "start tcpdump on host loopback (lo, tcp port 8080)"
  tcpdump -i lo -s 0 -w "$PCAP" 'tcp port 8080' &
  TPID=$!
  sleep 1
  ./scripts/e2e-curl.sh || true
  sleep 1
  kill -INT "$TPID" 2>/dev/null || true
  wait "$TPID" 2>/dev/null || true
  echo
  echo "capture saved: $PCAP ($(du -h "$PCAP" | cut -f1))"
  echo "--- HTTP digest ---"
  tcpdump -r "$PCAP" 'tcp port 8080' 2>/dev/null \
    | grep -aE 'HTTP:' | sed 's/^.*HTTP:/HTTP:/' | sort -u | head -60 || true
}

if [ "${CAPTURE_MODE:-container}" = "host" ]; then
  capture_host
else
  capture_container
fi