#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CONTAINER="${VIDAY_PG_CONTAINER:-viday_postgres}"
PGUSER="${VIDAY_PG_USER:-viday_admin_user}"
PGDB="${VIDAY_PG_DB:-viday}"

SCALES=(1000 5000 10000 50000 100000 250000 500000 750000 1000000)

if [[ -n "${N:-}" ]]; then
  SCALES=("$N")
fi

echo "==> Applying 04_multidim_scaling.sql (functions + tables)"
docker exec -i "$CONTAINER" psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 \
  < "$SCRIPT_DIR/04_multidim_scaling.sql"

echo "==> Ensuring indexes from init/05_indexes.sql"
docker exec -i "$CONTAINER" psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 \
  < "$PROJECT_ROOT/docker/postgres/init/05_indexes.sql"

for rows in "${SCALES[@]}"; do
  start=$(date +%s)
  docker exec -i "$CONTAINER" psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 \
    -c "SELECT viday.research_benchmark_scale(${rows}::bigint);"
  echo "  Elapsed: $(($(date +%s) - start))s"
done

echo ""
echo "==> Exporting CSV for charts"
docker exec -i "$CONTAINER" psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 <<'SQL'
\copy (SELECT * FROM viday.benchmark_q1_public_feed_chart ORDER BY content_rows, with_index DESC) TO '/tmp/benchmark_q1_public_feed_chart.csv' WITH CSV HEADER;
\copy (SELECT * FROM viday.benchmark_q2_name_search_chart ORDER BY content_rows, with_index DESC) TO '/tmp/benchmark_q2_name_search_chart.csv' WITH CSV HEADER;
\copy (SELECT * FROM viday.benchmark_q3_playlist_join_chart ORDER BY content_rows, with_index DESC) TO '/tmp/benchmark_q3_playlist_join_chart.csv' WITH CSV HEADER;
\copy (SELECT * FROM viday.benchmark_q1_public_feed ORDER BY content_rows, with_index, run_index) TO '/tmp/benchmark_q1_public_feed_raw.csv' WITH CSV HEADER;
\copy (SELECT * FROM viday.benchmark_q2_name_search ORDER BY content_rows, with_index, run_index) TO '/tmp/benchmark_q2_name_search_raw.csv' WITH CSV HEADER;
\copy (SELECT * FROM viday.benchmark_q3_playlist_join ORDER BY content_rows, with_index, run_index) TO '/tmp/benchmark_q3_playlist_join_raw.csv' WITH CSV HEADER;
SQL

mkdir -p "$PROJECT_ROOT/research_output"
for f in q1_public_feed_chart q2_name_search_chart q3_playlist_join_chart \
         q1_public_feed_raw q2_name_search_raw q3_playlist_join_raw; do
  docker cp "$CONTAINER:/tmp/benchmark_${f}.csv" "$PROJECT_ROOT/research_output/benchmark_${f}.csv" 2>/dev/null || true
done

cp "$PROJECT_ROOT/research_output/benchmark_q1_public_feed_chart.csv" "$PROJECT_ROOT/benchmark_q1_public_feed.csv" 2>/dev/null || true
cp "$PROJECT_ROOT/research_output/benchmark_q2_name_search_chart.csv" "$PROJECT_ROOT/benchmark_q2_name_search.csv" 2>/dev/null || true
cp "$PROJECT_ROOT/research_output/benchmark_q3_playlist_join_chart.csv" "$PROJECT_ROOT/benchmark_q3_playlist_join.csv" 2>/dev/null || true

echo ""
echo "Done. Chart CSV:"
ls -la "$PROJECT_ROOT/research_output/"*.csv 2>/dev/null || true
ls -la "$PROJECT_ROOT"/benchmark_q*.csv 2>/dev/null || true
