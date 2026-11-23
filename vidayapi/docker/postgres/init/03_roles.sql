SET search_path TO viday, public;

INSERT INTO viday.role (id, name) VALUES
    (1, 'GUEST'),
    (2, 'USER'),
    (3, 'CREATOR'),
    (4, 'ADMIN'),
    (5, 'ANALYST')
ON CONFLICT (id) DO NOTHING;

INSERT INTO viday.access_type (id, name) VALUES
    (1, 'PUBLIC'),
    (2, 'PRIVATE'),
    (3, 'FOLLOWERS')
ON CONFLICT (id) DO NOTHING;

INSERT INTO viday.content_type (id, name) VALUES
    (1, 'VIDEO'),
    (2, 'STREAM')
ON CONFLICT (id) DO NOTHING;

INSERT INTO viday.codec (id, name) VALUES
    (1, 'H264'),
    (2, 'VP9'),
    (3, 'AV1'),
    (4, 'H265')
ON CONFLICT (id) DO NOTHING;

INSERT INTO viday.stream_status (id, name) VALUES
    (1, 'SCHEDULED'),
    (2, 'LIVE'),
    (3, 'ENDED'),
    (4, 'ERROR')
ON CONFLICT (id) DO NOTHING;

-- Роли PostgreSQL
DO $$ BEGIN CREATE ROLE viday_guest; EXCEPTION WHEN duplicate_object THEN RAISE NOTICE 'Role viday_guest already exists'; END $$;
DO $$ BEGIN CREATE ROLE viday_user; EXCEPTION WHEN duplicate_object THEN RAISE NOTICE 'Role viday_user already exists'; END $$;
DO $$ BEGIN CREATE ROLE viday_creator; EXCEPTION WHEN duplicate_object THEN RAISE NOTICE 'Role viday_creator already exists'; END $$;
DO $$ BEGIN CREATE ROLE viday_admin; EXCEPTION WHEN duplicate_object THEN RAISE NOTICE 'Role viday_admin already exists'; END $$;
DO $$ BEGIN CREATE ROLE viday_analyst; EXCEPTION WHEN duplicate_object THEN RAISE NOTICE 'Role viday_analyst already exists'; END $$;

-- GUEST: только чтение публичного контента и плейлистов
GRANT CONNECT ON DATABASE viday TO viday_guest;
GRANT USAGE ON SCHEMA viday TO viday_guest;
GRANT SELECT ON viday.access_type TO viday_guest;
GRANT SELECT ON viday.content_type TO viday_guest;
GRANT SELECT ON viday.role TO viday_guest;
GRANT SELECT ON viday.codec TO viday_guest;
GRANT SELECT ON viday.stream_status TO viday_guest;
GRANT SELECT ON viday."user" TO viday_guest;
GRANT SELECT ON viday.content TO viday_guest;
GRANT SELECT ON viday.video TO viday_guest;
GRANT SELECT ON viday.media_variant TO viday_guest;
GRANT SELECT ON viday.playlist TO viday_guest;
GRANT SELECT ON viday.content_to_playlist TO viday_guest;
GRANT SELECT ON viday.stream TO viday_guest;

-- USER: подписки, плейлисты, контент для подписчиков (+ публичный через viday_guest)
GRANT CONNECT ON DATABASE viday TO viday_user;
GRANT USAGE ON SCHEMA viday TO viday_user;
GRANT viday_guest TO viday_user;
GRANT SELECT, INSERT, UPDATE ON viday."user" TO viday_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON viday.content TO viday_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON viday.video TO viday_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON viday.playlist TO viday_user;
GRANT SELECT, INSERT, DELETE ON viday.user_follows TO viday_user;
GRANT SELECT, INSERT, DELETE ON viday.user_to_playlist TO viday_user;
GRANT SELECT, INSERT, DELETE ON viday.content_to_playlist TO viday_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON viday.media_variant TO viday_user;
GRANT SELECT, INSERT, UPDATE ON viday.content_view_stats TO viday_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA viday TO viday_user;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA viday TO viday_user;

-- CREATOR: всё от USER + загрузка/управление своим контентом и стримами
GRANT CONNECT ON DATABASE viday TO viday_creator;
GRANT USAGE ON SCHEMA viday TO viday_creator;
GRANT viday_user TO viday_creator;
GRANT SELECT, INSERT, UPDATE, DELETE ON viday.stream TO viday_creator;

-- ADMIN: полный доступ к схеме (миграции, ручное администрирование)
GRANT CONNECT ON DATABASE viday TO viday_admin;
GRANT USAGE ON SCHEMA viday TO viday_admin;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA viday TO viday_admin;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA viday TO viday_admin;
GRANT ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA viday TO viday_admin;

-- ANALYST: статистика просмотров, ограниченный профиль пользователя
GRANT CONNECT ON DATABASE viday TO viday_analyst;
GRANT USAGE ON SCHEMA viday TO viday_analyst;
GRANT viday_guest TO viday_analyst;
ALTER ROLE viday_analyst BYPASSRLS;
GRANT SELECT ON viday.content_view_stats TO viday_analyst;
GRANT SELECT ON viday.stream TO viday_analyst;
GRANT SELECT (id, username, created_at) ON viday."user" TO viday_analyst;

-- Учётные записи для прямого доступа и runtime-приложения
DO $$ BEGIN CREATE USER viday_guest_user WITH PASSWORD 'guest'; EXCEPTION WHEN duplicate_object THEN RAISE NOTICE 'User viday_guest_user already exists'; END $$;
DO $$ BEGIN CREATE USER viday_user_user WITH PASSWORD 'user'; EXCEPTION WHEN duplicate_object THEN RAISE NOTICE 'User viday_user_user already exists'; END $$;
DO $$ BEGIN CREATE USER viday_creator_user WITH PASSWORD 'creator'; EXCEPTION WHEN duplicate_object THEN RAISE NOTICE 'User viday_creator_user already exists'; END $$;
DO $$ BEGIN CREATE USER viday_admin_user WITH PASSWORD 'admin'; EXCEPTION WHEN duplicate_object THEN RAISE NOTICE 'User viday_admin_user already exists'; END $$;
DO $$ BEGIN CREATE USER viday_analyst_user WITH PASSWORD 'analyst'; EXCEPTION WHEN duplicate_object THEN RAISE NOTICE 'User viday_analyst_user already exists'; END $$;
DO $$ BEGIN CREATE USER viday_app_user WITH PASSWORD 'app_password_change_me'; EXCEPTION WHEN duplicate_object THEN RAISE NOTICE 'User viday_app_user already exists'; END $$;

GRANT viday_guest TO viday_guest_user;
GRANT viday_user TO viday_user_user;
GRANT viday_creator TO viday_creator_user;
GRANT viday_admin TO viday_admin_user;
GRANT viday_analyst TO viday_analyst_user;
GRANT viday_guest, viday_user, viday_creator, viday_admin, viday_analyst TO viday_app_user;

GRANT CONNECT ON DATABASE viday TO viday_app_user;
GRANT USAGE ON SCHEMA viday TO viday_app_user;

-- Row Level Security
ALTER TABLE viday.content ENABLE ROW LEVEL SECURITY;
ALTER TABLE viday.playlist ENABLE ROW LEVEL SECURITY;
ALTER TABLE viday.content FORCE ROW LEVEL SECURITY;
ALTER TABLE viday.playlist FORCE ROW LEVEL SECURITY;

CREATE OR REPLACE FUNCTION viday.get_current_user_id()
    RETURNS INTEGER AS $$
BEGIN
    RETURN COALESCE(
        current_setting('app.current_user_id', true)::INTEGER,
        NULL
    );
EXCEPTION
    WHEN OTHERS THEN
        RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

DROP POLICY IF EXISTS content_public_select ON viday.content;
DROP POLICY IF EXISTS content_private_select ON viday.content;
DROP POLICY IF EXISTS content_followers_select ON viday.content;
DROP POLICY IF EXISTS content_owner_all ON viday.content;
DROP POLICY IF EXISTS content_admin_all ON viday.content;
DROP POLICY IF EXISTS content_owner_select ON viday.content;

DROP POLICY IF EXISTS playlist_public_select ON viday.playlist;
DROP POLICY IF EXISTS playlist_private_select ON viday.playlist;
DROP POLICY IF EXISTS playlist_followers_select ON viday.playlist;
DROP POLICY IF EXISTS playlist_owner_all ON viday.playlist;
DROP POLICY IF EXISTS playlist_user_insert ON viday.playlist;
DROP POLICY IF EXISTS playlist_user_update ON viday.playlist;
DROP POLICY IF EXISTS playlist_user_delete ON viday.playlist;
DROP POLICY IF EXISTS playlist_admin_all ON viday.playlist;
DROP POLICY IF EXISTS playlist_owner_select ON viday.playlist;

-- content: GUEST -- только PUBLIC
CREATE POLICY content_public_select ON viday.content
    FOR SELECT TO viday_guest
    USING (access_type_id = 1);

-- content: USER -- свой PRIVATE
CREATE POLICY content_private_select ON viday.content
    FOR SELECT TO viday_user
    USING (access_type_id = 2 AND owner_id = viday.get_current_user_id());

-- content: USER -- FOLLOWERS (свой или автор, на которого подписан)
CREATE POLICY content_followers_select ON viday.content
    FOR SELECT TO viday_user
    USING (
        access_type_id = 3
        AND (
            owner_id = viday.get_current_user_id()
            OR EXISTS (
                SELECT 1 FROM viday.user_follows
                WHERE followed_user_id = owner_id
                  AND following_user_id = viday.get_current_user_id()
            )
        )
    );

-- content: CREATOR -- полное управление только своим контентом (в т.ч. PRIVATE виден только ему)
CREATE POLICY content_owner_all ON viday.content
    FOR ALL TO viday_creator
    USING (owner_id = viday.get_current_user_id())
    WITH CHECK (owner_id = viday.get_current_user_id());

-- content: ADMIN -- без ограничений
CREATE POLICY content_admin_all ON viday.content
    FOR ALL TO viday_admin
    USING (true)
    WITH CHECK (true);

-- playlist: GUEST -- только PUBLIC
CREATE POLICY playlist_public_select ON viday.playlist
    FOR SELECT TO viday_guest
    USING (access_type_id = 1);

-- playlist: USER -- свой PRIVATE
CREATE POLICY playlist_private_select ON viday.playlist
    FOR SELECT TO viday_user
    USING (access_type_id = 2 AND owner_id = viday.get_current_user_id());

-- playlist: USER -- FOLLOWERS (свой или автор, на которого подписан)
CREATE POLICY playlist_followers_select ON viday.playlist
    FOR SELECT TO viday_user
    USING (
        access_type_id = 3
        AND (
            owner_id = viday.get_current_user_id()
            OR EXISTS (
                SELECT 1 FROM viday.user_follows
                WHERE followed_user_id = owner_id
                  AND following_user_id = viday.get_current_user_id()
            )
        )
    );

-- playlist: USER -- CRUD своих плейлистов
CREATE POLICY playlist_user_insert ON viday.playlist
    FOR INSERT TO viday_user
    WITH CHECK (owner_id = viday.get_current_user_id());

CREATE POLICY playlist_user_update ON viday.playlist
    FOR UPDATE TO viday_user
    USING (owner_id = viday.get_current_user_id())
    WITH CHECK (owner_id = viday.get_current_user_id());

CREATE POLICY playlist_user_delete ON viday.playlist
    FOR DELETE TO viday_user
    USING (owner_id = viday.get_current_user_id());

-- playlist: CREATOR -- полное управление своими плейлистами
CREATE POLICY playlist_owner_all ON viday.playlist
    FOR ALL TO viday_creator
    USING (owner_id = viday.get_current_user_id())
    WITH CHECK (owner_id = viday.get_current_user_id());

-- playlist: ADMIN -- без ограничений
CREATE POLICY playlist_admin_all ON viday.playlist
    FOR ALL TO viday_admin
    USING (true)
    WITH CHECK (true);
