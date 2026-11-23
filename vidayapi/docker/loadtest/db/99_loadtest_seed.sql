-- =============================================================================
-- ЛР4 — детерминированный сид нагрузочного стенда.
--
-- Выполняется PostgreSQL при первом старте ЧИСТОГО тома (docker-entrypoint-initdb.d)
-- после штатных init-скриптов 01..05. Одинаковый сид на каждый прогон —
-- обязательное условие сопоставимости (Требование 5).
--
-- Пользователи: 20 креаторов (id 1..20) + 200 зрителей (id 21..220).
-- Пароль всех пользователей: VidayLoadTest!42
--   BCrypt: $2b$10$fDbFe1OlSLX7G.V7yPVjUeTYrIQLpebqsjHsLSQrfdzXKMrR3QgdC
-- Контент: 1000 публичных видео, 100 публичных плейлистов, подписки.
-- =============================================================================
SET search_path TO viday, public;
SET ROLE viday_admin;

-- 20 креаторов + 200 обычных пользователей.
INSERT INTO viday."user" (id, username, password, role_id, created_at)
SELECT
    gs,
    'loadcreator' || gs,
    '$2b$10$fDbFe1OlSLX7G.V7yPVjUeTYrIQLpebqsjHsLSQrfdzXKMrR3QgdC',
    3,
    CURRENT_TIMESTAMP - make_interval(mins => 10000 - gs)
FROM generate_series(1, 20) AS gs
ON CONFLICT (id) DO NOTHING;

INSERT INTO viday."user" (id, username, password, role_id, created_at)
SELECT
    gs,
    'loaduser' || (gs - 20),
    '$2b$10$fDbFe1OlSLX7G.V7yPVjUeTYrIQLpebqsjHsLSQrfdzXKMrR3QgdC',
    2,
    CURRENT_TIMESTAMP - make_interval(mins => 9000 - gs)
FROM generate_series(21, 220) AS gs
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('viday."user"', 'id'), 220, true);

-- 1000 публичных видео, распределённых по 20 креаторам.
INSERT INTO viday.content
    (id, content_type_id, name, description, source, owner_id, access_type_id, created_at)
SELECT
    gs,
    1,
    'Seed video ' || gs,
    'Load-test video #' || gs || ' (public)',
    'http://127.0.0.1:9000/viday/seed/video-' || gs || '.mp4',
    1 + ((gs - 1) % 20),
    1,
    CURRENT_TIMESTAMP - make_interval(mins => 2000 - gs)
FROM generate_series(1, 1000) AS gs
ON CONFLICT (id) DO NOTHING;

INSERT INTO viday.video (content_id, duration_seconds, preview)
SELECT
    gs,
    30 + (gs % 300),
    'http://127.0.0.1:9000/viday/seed/preview-' || gs || '.jpg'
FROM generate_series(1, 1000) AS gs
ON CONFLICT (content_id) DO NOTHING;

INSERT INTO viday.media_variant
    (content_id, width, height, bitrate, codec_id, is_source, file_path, file_size_bytes, created_at)
SELECT
    gs, 1280, 720, 2000000, 1, TRUE,
    'http://127.0.0.1:9000/viday/seed/video-' || gs || '.mp4',
    1048576 + gs,
    CURRENT_TIMESTAMP - make_interval(mins => 2000 - gs)
FROM generate_series(1, 1000) AS gs
ON CONFLICT (content_id, width, height, bitrate, codec_id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('viday.content', 'id'), 1000, true);
SELECT setval(pg_get_serial_sequence('viday."media_variant"', 'id'),
              COALESCE((SELECT MAX(id) FROM viday.media_variant), 1), true);

-- 100 публичных плейлистов по 5 видео.
INSERT INTO viday.playlist (id, name, owner_id, access_type_id, created_at)
SELECT
    gs,
    'Seed public playlist ' || gs,
    1 + ((gs - 1) % 20),
    1,
    CURRENT_TIMESTAMP - make_interval(mins => 1000 - gs)
FROM generate_series(1, 100) AS gs
ON CONFLICT (id) DO NOTHING;

INSERT INTO viday.content_to_playlist (content_id, playlist_id, position, added_at)
SELECT
    1 + (((p.gs * 37) + (k.gs * 13)) % 1000),
    p.gs,
    k.gs,
    CURRENT_TIMESTAMP - make_interval(mins => 1000 - p.gs)
FROM generate_series(1, 100) AS p(gs)
CROSS JOIN generate_series(1, 5) AS k(gs)
ON CONFLICT DO NOTHING;

SELECT setval(pg_get_serial_sequence('viday.playlist', 'id'), 100, true);

-- Часть пользователей уже подписана на креаторов: лента «available playlists»
-- получается непустой, а follow/unfollow в сценарии проверяет и 200, и 409.
INSERT INTO viday.user_follows (following_user_id, followed_user_id, created_at)
SELECT
    u.gs,
    1 + ((u.gs * 7) % 20),
    CURRENT_TIMESTAMP - make_interval(mins => 500 - u.gs)
FROM generate_series(21, 220, 2) AS u(gs)
ON CONFLICT DO NOTHING;

-- Статистика просмотров — для реалистичности аналитических запросов.
INSERT INTO viday.content_view_stats (content_id, view_count, last_viewed_at)
SELECT gs, (gs * 17) % 100000, CURRENT_TIMESTAMP - make_interval(mins => gs % 500)
FROM generate_series(1, 1000) AS gs
ON CONFLICT (content_id) DO NOTHING;

RESET ROLE;

SELECT 'LR4 seed: users=' || COUNT(*) FROM viday."user";
SELECT 'LR4 seed: videos=' || COUNT(*) FROM viday.video;
SELECT 'LR4 seed: playlists=' || COUNT(*) FROM viday.playlist;
