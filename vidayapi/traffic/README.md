# Задание 5 ЛР2: имитация E2E через curl + захват трафика (tcpdump/Wireshark)

Директория `traffic/` хранит артефакты демонстрации **Задания 5**: E2E-сценарий
имитируется средством отправки запросов (**curl**), а лог снимается средством
захвата трафика (**tcpdump**) и открывается в **Wireshark**.

## Как получить свежий capture (на машине студента, Linux)

```bash
cd vidayapi
make stand-up                  # тестовый стенд (postgres/redis/minio, порты 55432/56380/59002)
make app-up                    # контейнер приложения (Dockerfile.app, network_mode: host)
make traffic-demo              # e2e-curl.sh + tcpdump -> traffic/viday-e2e-<ts>.pcap
```

После прогона:

- `traffic/e2e-curl-<ts>.json` — все ответы API сценария (9 шагов);
- `traffic/viday-e2e-<ts>.pcap` — захват; открыть в Wireshark и применить фильтр
  `tcp.port == 8080` (трафик идёт по loopback, локальный адрес может быть `::1`).

Без Docker (приложение запущено на хосте, `make run`):
`CAPTURE_MODE=host sudo ./scripts/traffic-capture.sh`.

## Пример для защиты

В [`examples/viday-e2e-sample.pcap`](examples/viday-e2e-sample.pcap) лежит готовый
минимальный capture (генерируется скриптом
[`scripts/make-sample-pcap.py`](../scripts/make-sample-pcap.py)): пара пакетов
«HTTP GET /api/videos → HTTP 200» поверх Ethernet/IPv4/TCP. Он открывается в
Wireshark без сетевого окружения — удобно показать «лог E2E-прогона» прямо на
защите, даже если свежий capture не удалось снять.

Перегенерировать пример:

```bash
python3 scripts/make-sample-pcap.py -o traffic/examples/viday-e2e-sample.pcap
```

> Примечание: capture'ы и json'ы реальных прогонов не коммитятся (см.
> [`../.gitignore`](../.gitignore)); в репозитории только этот README и пример.