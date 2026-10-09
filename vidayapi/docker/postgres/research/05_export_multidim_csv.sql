SET search_path TO viday, public;

\echo '--- Q1: Публичная лента (для графика) ---'
SELECT * FROM viday.benchmark_q1_public_feed_chart ORDER BY content_rows, with_index DESC;

\echo '--- Q2: Поиск ILIKE ---'
SELECT * FROM viday.benchmark_q2_name_search_chart ORDER BY content_rows, with_index DESC;

\echo '--- Q3: Контент плейлиста JOIN ---'
SELECT * FROM viday.benchmark_q3_playlist_join_chart ORDER BY content_rows, with_index DESC;

COPY (SELECT * FROM viday.benchmark_q1_public_feed_chart ORDER BY content_rows, with_index DESC)
TO '/tmp/benchmark_q1_public_feed_chart.csv' WITH CSV HEADER;

COPY (SELECT * FROM viday.benchmark_q2_name_search_chart ORDER BY content_rows, with_index DESC)
TO '/tmp/benchmark_q2_name_search_chart.csv' WITH CSV HEADER;

COPY (SELECT * FROM viday.benchmark_q3_playlist_join_chart ORDER BY content_rows, with_index DESC)
TO '/tmp/benchmark_q3_playlist_join_chart.csv' WITH CSV HEADER;
