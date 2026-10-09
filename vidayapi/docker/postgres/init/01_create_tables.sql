CREATE SCHEMA IF NOT EXISTS viday;

CREATE TABLE viday.access_type (
    id SERIAL,
    name VARCHAR(50) NOT NULL
);

CREATE TABLE viday.content_type (
    id SERIAL,
    name VARCHAR(50) NOT NULL
);

CREATE TABLE viday.role (
    id SERIAL,
    name VARCHAR(50) NOT NULL
);

CREATE TABLE viday.codec (
    id SERIAL,
    name VARCHAR(50) NOT NULL
);

CREATE TABLE viday.stream_status (
    id SERIAL,
    name VARCHAR(50) NOT NULL
);

CREATE TABLE viday."user" (
    id SERIAL,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(255) NOT NULL,
    role_id INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE viday.content (
    id SERIAL,
    content_type_id INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    source VARCHAR(500),
    owner_id INTEGER NOT NULL,
    access_type_id INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE viday.media_variant (
    id SERIAL,
    content_id INTEGER NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    bitrate INTEGER NOT NULL,
    codec_id INTEGER NOT NULL,
    is_source BOOLEAN DEFAULT FALSE,
    file_path VARCHAR(500) NOT NULL,
    file_size_bytes BIGINT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE viday.video (
    content_id INTEGER NOT NULL,
    duration_seconds INTEGER NOT NULL,
    preview VARCHAR(1000) NOT NULL
);

CREATE TABLE viday.stream (
    content_id INTEGER NOT NULL,
    stream_status_id INTEGER NOT NULL,
    scheduled_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    ended_at TIMESTAMP WITH TIME ZONE,
    stream_key VARCHAR(64) UNIQUE
);

CREATE TABLE viday.playlist (
    id SERIAL,
    name VARCHAR(255) NOT NULL,
    owner_id INTEGER NOT NULL,
    access_type_id INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE viday.user_follows (
    following_user_id INTEGER NOT NULL,
    followed_user_id INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE viday.user_to_playlist (
    user_id INTEGER NOT NULL,
    playlist_id INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE viday.content_to_playlist (
    content_id INTEGER NOT NULL,
    playlist_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    added_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE viday.content_view_stats (
    content_id INTEGER NOT NULL,
    view_count BIGINT DEFAULT 0,
    last_viewed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (content_id)
);
