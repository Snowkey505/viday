# =============================================================================
# ЛР4 — экспортёр ресурсов Docker-контейнеров для Prometheus.
#
# Использует Docker Engine API через /var/run/docker.sock (только stdlib Python)
# и отдаёт /metrics в формате Prometheus. Дополнительно пишет сырые сэмплы в
# /data/container-samples.jsonl — из них scripts/analyze-loadtest.py строит
# min/max/median по каждому компоненту (Требование 8).
# =============================================================================
FROM python:3.12-slim

WORKDIR /app
COPY docker/loadtest/exporter/exporter.py /app/exporter.py
ENV PYTHONUNBUFFERED=1
EXPOSE 9101
ENTRYPOINT ["python3", "/app/exporter.py"]
