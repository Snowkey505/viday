SET search_path TO viday, public;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_content_access_created
    ON viday.content (access_type_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_content_owner_access
    ON viday.content (owner_id, access_type_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_content_name_trgm
    ON viday.content USING gin (name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_content_to_playlist_position
    ON viday.content_to_playlist (playlist_id, position);

CREATE INDEX IF NOT EXISTS idx_media_variant_content_bitrate
    ON viday.media_variant (content_id, bitrate DESC);

CREATE INDEX IF NOT EXISTS idx_stream_status_live
    ON viday.stream (stream_status_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_follows_followed
    ON viday.user_follows (followed_user_id, following_user_id);
