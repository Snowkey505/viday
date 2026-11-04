#!/usr/bin/env bash
# =============================================================================
# ЛР3 — запуск микробенчмарка в Docker.
#
# Требование 6: каждый прогон — ОТДЕЛЬНЫЙ docker-образ с ОДНОЙ И ТОЙ ЖЕ
# конфигурацией. Скрипт на каждый прогон собирает образ из
# docker/benchmark/Dockerfile (слои Gradle кэшируются, пересборка дешёвая)
# и запускает его:
#   * с закреплением на 1 CPU (--cpuset-cpus, Требование 4);
#   * с теми же JVM-параметрами (ENV JAVA_OPTS в Dockerfile);
#   * с уникальным именем контейнера и монтированием каталога результатов.
#
# Запуск:  ./scripts/run-benchmark.sh
# Параметры (переменные окружения): RUN_ID, CPUSET (по умолчанию 0),
# SAMPLES / WARMUP / TEARDOWN / BATCH / MIXED_PAIRS, OUT (результаты).
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE="viday-bench:latest"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
CPUSET="${CPUSET:-0}"
SAMPLES="${SAMPLES:-1000}"
WARMUP="${WARMUP:-5000}"
SETTLE="${SETTLE:-200}"
DROP="${DROP:-10}"
TEARDOWN="${TEARDOWN:-250}"
BATCH="${BATCH:-256}"
MIXED_PAIRS="${MIXED_PAIRS:-256}"
OUT_DIR="${OUT_DIR:-benchmark/build/reports/benchmark/$RUN_ID}"

echo "==> [LR3] run-id: $RUN_ID, cpuset: $CPUSET (1 ядро, Требование 4)"
echo "==> [LR3] конфигурация: samples=$SAMPLES warmup=$WARMUP settle=$SETTLE drop=$DROP teardown=$TEARDOWN batch=$BATCH mixedPairs=$MIXED_PAIRS"

# Требование 6: свежий docker-образ на каждый прогон, конфигурация одинаковая.
# --network=host: DNS внутри buildkit-контейнера не резолвит services.gradle.org /
# plugins.gradle.org (хост их видит), поэтому сборка идёт через сеть хоста.
echo "==> [LR3] сборка образа $IMAGE (для каждого прогона — отдельный образ)"
docker build --network=host -f docker/benchmark/Dockerfile -t "$IMAGE" .

mkdir -p "$OUT_DIR"

echo "==> [LR3] запуск контейнера viday-bench-$RUN_ID"
docker run --rm \
    --name "viday-bench-$RUN_ID" \
    --cpuset-cpus="$CPUSET" \
    --cpus=1 \
    --memory=1g \
    -e "BENCH_SAMPLES=$SAMPLES" \
    -e "BENCH_WARMUP=$WARMUP" \
    -e "BENCH_SETTLE=$SETTLE" \
    -e "BENCH_DROP=$DROP" \
    -e "BENCH_TEARDOWN=$TEARDOWN" \
    -e "BENCH_BATCH=$BATCH" \
    -e "BENCH_MIXED_PAIRS=$MIXED_PAIRS" \
    -v "$PWD/$OUT_DIR:/out" \
    "$IMAGE" --out /out

echo "==> [LR3] результаты: $OUT_DIR"
# Копия отчётов и графиков в docs/lr3-charts — материал отчёта по ЛР3.
mkdir -p docs/lr3-charts/$RUN_ID
cp -r "$OUT_DIR/report.md" "$OUT_DIR/report.html" "$OUT_DIR/charts" docs/lr3-charts/$RUN_ID/ 2>/dev/null || true
rm -rf docs/lr3-charts/latest
cp -r docs/lr3-charts/$RUN_ID docs/lr3-charts/latest
echo "==> [LR3] копия отчёта/графиков: docs/lr3-charts/$RUN_ID"

# Отчёт и ВСЕ графики перенесены в Grafana: импортируем результаты в PostgreSQL
# стенда визуализации (если он поднят: make grafana-up).
if docker inspect viday_bench_postgres >/dev/null 2>&1; then
  echo "==> [LR3] импорт результатов в Grafana DB (run-id: $RUN_ID)"
  RUN_ID="$RUN_ID" ./scripts/import-benchmark-to-db.sh "$OUT_DIR/report.json" || \
    echo "==> [LR3] импорт пропущен (см. выше)"
  echo "==> [LR3] дашборд: http://localhost:3000/d/benchmark-lr3 (Grafana, admin/admin)"
else
  echo "==> [LR3] стенд Grafana не поднят — импорт пропущен. Поднимите: make grafana-up, затем: make benchmark-import"
fi