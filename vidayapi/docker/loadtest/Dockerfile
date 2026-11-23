# =============================================================================
# ЛР4 — образ генератора нагрузки k6.
#
# На каждый прогон scripts/run-loadtest.sh собирает ОТДЕЛЬНЫЙ образ с уникальным
# тегом (--build-arg RUN_ID) из этого Dockerfile, но с одной и той же
# конфигурацией. Так выполняются требования 5 (равные условия) и 12 (генератор
# закрепляется на отдельных CPU-ядрах, не конкурируя с API/БД/мониторингом).
# =============================================================================
FROM grafana/k6:0.54.0

ARG RUN_ID=local
LABEL org.opencontainers.image.title="viday-lr4-k6"
LABEL com.viday.loadtest.run_id="${RUN_ID}"

WORKDIR /scripts
# Контекст сборки — каталог loadtest (см. scripts/run-loadtest.sh), поэтому
# сценарий лежит в корне контекста.
COPY scenario.js /scripts/scenario.js
ENV K6_NO_USAGE_REPORT=true \
    K6_NO_COLOR=true

ENTRYPOINT ["k6"]
