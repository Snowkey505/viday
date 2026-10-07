SET search_path TO viday, public;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

INSERT INTO viday.role (id, name) VALUES
    (1, 'GUEST'), (2, 'USER'), (3, 'CREATOR'), (4, 'ADMIN'), (5, 'ANALYST')
ON CONFLICT (id) DO NOTHING;
INSERT INTO viday.access_type (id, name) VALUES (1, 'PUBLIC'), (2, 'PRIVATE'), (3, 'FOLLOWERS')
ON CONFLICT (id) DO NOTHING;
INSERT INTO viday.content_type (id, name) VALUES (1, 'VIDEO'), (2, 'STREAM')
ON CONFLICT (id) DO NOTHING;
INSERT INTO viday.codec (id, name) VALUES (1, 'H264'), (2, 'VP9'), (3, 'AV1'), (4, 'H265')
ON CONFLICT (id) DO NOTHING;

-- Таблицы результатов

DROP TABLE IF EXISTS viday.benchmark_q1_public_feed CASCADE;
DROP TABLE IF EXISTS viday.benchmark_q2_name_search CASCADE;
DROP TABLE IF EXISTS viday.benchmark_q3_playlist_join CASCADE;

CREATE TABLE viday.benchmark_q1_public_feed (
    id SERIAL PRIMARY KEY,
    content_rows BIGINT NOT NULL,
    with_index BOOLEAN NOT NULL,
    execution_time_ms NUMERIC NOT NULL,
    rows_returned BIGINT NOT NULL,
    run_index SMALLINT NOT NULL DEFAULT 1,
    tested_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE viday.benchmark_q2_name_search (
    id SERIAL PRIMARY KEY,
    content_rows BIGINT NOT NULL,
    with_index BOOLEAN NOT NULL,
    execution_time_ms NUMERIC NOT NULL,
    rows_returned BIGINT NOT NULL,
    run_index SMALLINT NOT NULL DEFAULT 1,
    tested_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE viday.benchmark_q3_playlist_join (
    id SERIAL PRIMARY KEY,
    content_rows BIGINT NOT NULL,
    with_index BOOLEAN NOT NULL,
    execution_time_ms NUMERIC NOT NULL,
    rows_returned BIGINT NOT NULL,
    run_index SMALLINT NOT NULL DEFAULT 1,
    tested_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- Замер одного SQL (мс)

CREATE OR REPLACE FUNCTION viday.research_timed_query(p_with_index BOOLEAN, p_sql TEXT)
RETURNS NUMERIC
LANGUAGE plpgsql
SET search_path = viday, public
AS $$
DECLARE
    t0 TIMESTAMPTZ;
    t1 TIMESTAMPTZ;
    cnt BIGINT;
BEGIN
    IF p_with_index THEN
        SET LOCAL enable_seqscan = on;
        SET LOCAL enable_indexscan = on;
        SET LOCAL enable_bitmapscan = on;
    ELSE
        SET LOCAL enable_indexscan = off;
        SET LOCAL enable_bitmapscan = off;
        SET LOCAL enable_seqscan = on;
    END IF;

    DROP TABLE IF EXISTS _bench_tmp;
    t0 := clock_timestamp();
    EXECUTE 'CREATE TEMP TABLE _bench_tmp AS ' || p_sql;
    EXECUTE 'SELECT COUNT(*) FROM _bench_tmp' INTO cnt;
    t1 := clock_timestamp();

    RETURN ROUND((EXTRACT(EPOCH FROM (t1 - t0)) * 1000)::NUMERIC, 3);
END;
$$;

-- Наполнение БД под заданный объём content

CREATE OR REPLACE FUNCTION viday.research_seed_content_volume(p_content_rows BIGINT)
RETURNS void
LANGUAGE plpgsql
SET search_path = viday, public
AS $$
DECLARE
    v_users INT := LEAST(10000, GREATEST(500, (p_content_rows / 100)::INT));
    v_playlists INT := GREATEST(200, LEAST(50000, (p_content_rows / 50)::INT));
    v_links BIGINT := GREATEST(p_content_rows / 5, 1000);
    v_playlist_target CONSTANT INT := 100;
    v_fill_playlist INT;
BEGIN
  RAISE NOTICE 'Seeding % content rows (users=%, playlists=%)...', p_content_rows, v_users, v_playlists;

  TRUNCATE TABLE
      content_to_playlist,
      user_to_playlist,
      user_follows,
      media_variant,
      video,
      stream,
      content_view_stats,
      content,
      playlist,
      "user"
  RESTART IDENTITY CASCADE;

  INSERT INTO "user" (username, password, role_id)
  SELECT 'bench_user_' || i, '$2a$10$hash', 2
  FROM generate_series(1, v_users) AS i;

  INSERT INTO content (content_type_id, name, description, source, owner_id, access_type_id, created_at)
  SELECT
      1,
      'Video Title ' || i,
      'Description ' || i,
      '/videos/source_' || i || '.mp4',
      1 + ((i - 1) % v_users),
      CASE
          WHEN i % 5 = 0 THEN 2
          WHEN i % 3 = 0 THEN 3
          ELSE 1
      END,
      CURRENT_TIMESTAMP - ((i % 365) || ' days')::INTERVAL
  FROM generate_series(1, p_content_rows) AS i;

  INSERT INTO video (content_id, duration_seconds, preview)
  SELECT id, 60 + (id % 3600), 'https://example.com/preview/' || id
  FROM content
  WHERE content_type_id = 1;

  INSERT INTO playlist (id, name, owner_id, access_type_id)
  SELECT
      i,
      CASE WHEN i = v_playlist_target THEN 'Benchmark Playlist 100' ELSE 'Playlist ' || i END,
      1 + ((i - 1) % v_users),
      CASE WHEN i = v_playlist_target THEN 1 WHEN i % 4 = 0 THEN 2 ELSE 1 END
  FROM generate_series(1, GREATEST(v_playlists, v_playlist_target)) AS s(i);

  PERFORM setval(
      pg_get_serial_sequence('viday.playlist', 'id'),
      GREATEST(v_playlists, v_playlist_target)
  );

  v_fill_playlist := LEAST(500, GREATEST(20, (p_content_rows / 200)::INT));

  ALTER TABLE content_to_playlist DISABLE TRIGGER trg_validate_playlist_position;
  ALTER TABLE content_to_playlist DISABLE TRIGGER trg_check_content_playlist_privacy_restrictions;

  INSERT INTO content_to_playlist (content_id, playlist_id, position)
  SELECT c.id, v_playlist_target, ROW_NUMBER() OVER (ORDER BY c.id)
  FROM (
      SELECT id FROM content WHERE access_type_id = 1 ORDER BY id LIMIT v_fill_playlist
  ) c;

  INSERT INTO content_to_playlist (content_id, playlist_id, position)
  SELECT
      content_id,
      playlist_id,
      ROW_NUMBER() OVER (PARTITION BY playlist_id ORDER BY rn)
  FROM (
      SELECT
          c.id AS content_id,
          p.id AS playlist_id,
          g AS rn
      FROM generate_series(1, v_links) AS g
      JOIN LATERAL (
          SELECT id
          FROM content
          WHERE access_type_id = 1
          ORDER BY id
          OFFSET (g % GREATEST((SELECT COUNT(*)::INT FROM content WHERE access_type_id = 1), 1))
          LIMIT 1
      ) c ON TRUE
      JOIN LATERAL (
          SELECT id
          FROM playlist
          WHERE access_type_id = 1 AND id <> v_playlist_target
          ORDER BY id
          OFFSET (g % GREATEST(v_playlists - 1, 1))
          LIMIT 1
      ) p ON TRUE
  ) bulk
  ON CONFLICT (content_id, playlist_id) DO NOTHING;

  ALTER TABLE content_to_playlist ENABLE TRIGGER trg_validate_playlist_position;
  ALTER TABLE content_to_playlist ENABLE TRIGGER trg_check_content_playlist_privacy_restrictions;

  ANALYZE content;
  ANALYZE content_to_playlist;
  ANALYZE playlist;

  RAISE NOTICE 'Seed done: content=%, public=%, ctp=%',
      (SELECT COUNT(*) FROM content),
      (SELECT COUNT(*) FROM content WHERE access_type_id = 1),
      (SELECT COUNT(*) FROM content_to_playlist);
END;
$$;

-- Замеры трёх запросов (по 50 прогонов)

DROP FUNCTION IF EXISTS viday.research_timed_query(BOOLEAN, TEXT);
DROP FUNCTION IF EXISTS viday.research_timed_query(p_with_index BOOLEAN, p_sql TEXT);

CREATE OR REPLACE FUNCTION viday.research_run_query_suite(p_content_rows BIGINT)
RETURNS void
LANGUAGE plpgsql
SET search_path = viday, public
AS $$
DECLARE
    q1 TEXT := $q$
        SELECT c.id, c.name, c.owner_id, c.created_at
        FROM viday.content c
        WHERE c.access_type_id = 1
        ORDER BY c.created_at DESC
        LIMIT 20
    $q$;
    q2 TEXT := $q$
        SELECT c.id, c.name, c.owner_id
        FROM viday.content c
        WHERE c.name ILIKE '%Video Title 5%'
        LIMIT 20
    $q$;
    q3 TEXT := $q$
        SELECT c.id, c.name, cp.position
        FROM viday.content c
        INNER JOIN viday.content_to_playlist cp ON c.id = cp.content_id
        WHERE cp.playlist_id = 100
        ORDER BY cp.position
        LIMIT 20
    $q$;
    r BOOLEAN;
    ms NUMERIC;
    runs INT := 50;
    i INT;
BEGIN
    FOR i IN 1..runs LOOP
        FOREACH r IN ARRAY ARRAY[true, false] LOOP
            ms := viday.research_timed_query(r::BOOLEAN, q1::TEXT);
            INSERT INTO viday.benchmark_q1_public_feed (content_rows, with_index, execution_time_ms, rows_returned, run_index)
            VALUES (p_content_rows, r, ms, 20, i);

            ms := viday.research_timed_query(r::BOOLEAN, q2::TEXT);
            INSERT INTO viday.benchmark_q2_name_search (content_rows, with_index, execution_time_ms, rows_returned, run_index)
            VALUES (p_content_rows, r, ms, 20, i);

            ms := viday.research_timed_query(r::BOOLEAN, q3::TEXT);
            INSERT INTO viday.benchmark_q3_playlist_join (content_rows, with_index, execution_time_ms, rows_returned, run_index)
            VALUES (p_content_rows, r, ms, 20, i);
        END LOOP;
    END LOOP;
END;
$$;

-- Полный цикл: seed + benchmark для одного объёма

CREATE OR REPLACE FUNCTION viday.research_benchmark_scale(p_content_rows BIGINT)
RETURNS void
LANGUAGE plpgsql
SET search_path = viday, public
AS $$
BEGIN
    PERFORM viday.research_seed_content_volume(p_content_rows);
    PERFORM viday.research_run_query_suite(p_content_rows);
    RAISE NOTICE 'Benchmark complete for % rows', p_content_rows;
END;
$$;

-- Сводки для графиков (среднее по прогонам)

CREATE OR REPLACE VIEW viday.benchmark_q1_public_feed_chart AS
SELECT
    content_rows,
    with_index,
    ROUND(AVG(execution_time_ms), 3) AS avg_execution_time_ms,
    MIN(execution_time_ms) AS min_execution_time_ms,
    MAX(execution_time_ms) AS max_execution_time_ms
FROM viday.benchmark_q1_public_feed
GROUP BY content_rows, with_index
ORDER BY content_rows, with_index DESC;

CREATE OR REPLACE VIEW viday.benchmark_q2_name_search_chart AS
SELECT
    content_rows,
    with_index,
    ROUND(AVG(execution_time_ms), 3) AS avg_execution_time_ms,
    MIN(execution_time_ms) AS min_execution_time_ms,
    MAX(execution_time_ms) AS max_execution_time_ms
FROM viday.benchmark_q2_name_search
GROUP BY content_rows, with_index
ORDER BY content_rows, with_index DESC;

CREATE OR REPLACE VIEW viday.benchmark_q3_playlist_join_chart AS
SELECT
    content_rows,
    with_index,
    ROUND(AVG(execution_time_ms), 3) AS avg_execution_time_ms,
    MIN(execution_time_ms) AS min_execution_time_ms,
    MAX(execution_time_ms) AS max_execution_time_ms
FROM viday.benchmark_q3_playlist_join
GROUP BY content_rows, with_index
ORDER BY content_rows, with_index DESC;
