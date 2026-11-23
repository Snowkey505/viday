SET search_path TO viday, public;

CREATE OR REPLACE FUNCTION check_content_playlist_privacy_restrictions()
    RETURNS TRIGGER AS $$
DECLARE
    content_access INTEGER;
    playlist_access INTEGER;
BEGIN
    SELECT access_type_id INTO content_access
    FROM viday.content WHERE id = NEW.content_id;

    SELECT access_type_id INTO playlist_access
    FROM viday.playlist WHERE id = NEW.playlist_id;

    IF content_access = 2 AND playlist_access != 2 THEN
        RAISE EXCEPTION 'Cant add private content to non-private playlist. Content ID: %, Playlist ID: %',
            NEW.content_id, NEW.playlist_id
            USING ERRCODE = '23514';
    END IF;

    IF content_access = 3 AND playlist_access = 1 THEN
        RAISE EXCEPTION 'Cant add followers-only content to public playlist. Content ID: %, Playlist ID: %',
            NEW.content_id, NEW.playlist_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_check_content_playlist_privacy_restrictions
    BEFORE INSERT OR UPDATE ON viday.content_to_playlist
    FOR EACH ROW
EXECUTE FUNCTION check_content_playlist_privacy_restrictions();

CREATE OR REPLACE FUNCTION check_playlist_update_privacy_restrictions()
    RETURNS TRIGGER AS $$
DECLARE
    has_restricted_content BOOLEAN;
BEGIN
    IF OLD.access_type_id = NEW.access_type_id THEN
        RETURN NEW;
    END IF;

    IF NEW.access_type_id = 1 THEN
        SELECT EXISTS(
            SELECT 1 FROM viday.content c
                              JOIN viday.content_to_playlist cp ON cp.content_id = c.id
            WHERE cp.playlist_id = NEW.id
              AND c.access_type_id IN (2, 3)
        ) INTO has_restricted_content;

        IF has_restricted_content THEN
            RAISE EXCEPTION 'Cant change playlist to PUBLIC because it contains PRIVATE or FOLLOWERS content. Playlist ID: %',
                NEW.id
                USING ERRCODE = '23514';
        END IF;

    ELSIF NEW.access_type_id = 3 THEN
        SELECT EXISTS(
            SELECT 1 FROM viday.content c
                              JOIN viday.content_to_playlist cp ON cp.content_id = c.id
            WHERE cp.playlist_id = NEW.id
              AND c.access_type_id = 2
        ) INTO has_restricted_content;

        IF has_restricted_content THEN
            RAISE EXCEPTION 'Cant change playlist to FOLLOWERS because it contains PRIVATE content. Playlist ID: %',
                NEW.id
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_check_playlist_update_privacy_restrictions
    BEFORE UPDATE ON viday.playlist
    FOR EACH ROW
EXECUTE FUNCTION check_playlist_update_privacy_restrictions();

CREATE OR REPLACE FUNCTION check_content_update_privacy_restrictions()
    RETURNS TRIGGER AS $$
DECLARE
    has_restricted_playlist BOOLEAN;
BEGIN
    IF OLD.access_type_id = NEW.access_type_id THEN
        RETURN NEW;
    END IF;

    IF NEW.access_type_id = 2 THEN
        SELECT EXISTS(
            SELECT 1 FROM viday.playlist p
                              JOIN viday.content_to_playlist cp ON cp.playlist_id = p.id
            WHERE cp.content_id = NEW.id
              AND p.access_type_id IN (1, 3)
        ) INTO has_restricted_playlist;

        IF has_restricted_playlist THEN
            RAISE EXCEPTION 'Cant change content to PRIVATE because it exists in PUBLIC or FOLLOWERS playlists. Content ID: %',
                NEW.id
                USING ERRCODE = '23514';
        END IF;

    ELSIF NEW.access_type_id = 3 THEN
        SELECT EXISTS(
            SELECT 1 FROM viday.playlist p
                              JOIN viday.content_to_playlist cp ON cp.playlist_id = p.id
            WHERE cp.content_id = NEW.id
              AND p.access_type_id = 1
        ) INTO has_restricted_playlist;

        IF has_restricted_playlist THEN
            RAISE EXCEPTION 'Cant change content to FOLLOWERS because it exists in PUBLIC playlists. Content ID: %',
                NEW.id
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_check_content_update_privacy_restrictions
    BEFORE UPDATE ON viday.content
    FOR EACH ROW
EXECUTE FUNCTION check_content_update_privacy_restrictions();

CREATE OR REPLACE FUNCTION validate_playlist_position()
RETURNS TRIGGER AS $$
DECLARE
    max_position INTEGER;
BEGIN
    SELECT COALESCE(MAX(position), 0) INTO max_position
    FROM viday.content_to_playlist WHERE playlist_id = NEW.playlist_id;
    
    IF NEW.position <= 0 OR NEW.position > max_position + 1 THEN
        RAISE EXCEPTION 'BUSINESS_RULE_VIOLATION: Invalid position %. Must be between 1 and %', 
            NEW.position, max_position + 1
        USING ERRCODE = '23514';
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validate_playlist_position
    BEFORE INSERT ON viday.content_to_playlist
    FOR EACH ROW
    EXECUTE FUNCTION validate_playlist_position();

CREATE OR REPLACE FUNCTION ensure_single_source_variant()
RETURNS TRIGGER AS $$
DECLARE
    source_count INTEGER;
BEGIN
    IF NEW.is_source = TRUE THEN
        SELECT COUNT(*) INTO source_count
        FROM viday.media_variant 
        WHERE content_id = NEW.content_id 
        AND is_source = TRUE
        AND id != COALESCE(NEW.id, 0);
        
        IF source_count > 0 THEN
            RAISE EXCEPTION 'BUSINESS_RULE_VIOLATION: Only one source variant allowed per content. Content ID: %', 
                NEW.content_id
            USING ERRCODE = '23514';
        END IF;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ensure_single_source_variant
    BEFORE INSERT OR UPDATE ON viday.media_variant
    FOR EACH ROW
    EXECUTE FUNCTION ensure_single_source_variant();

CREATE OR REPLACE FUNCTION increment_view_count()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO viday.content_view_stats (content_id, view_count, last_viewed_at)
    VALUES (NEW.content_id, 1, CURRENT_TIMESTAMP)
    ON CONFLICT (content_id) 
    DO UPDATE SET 
        view_count = viday.content_view_stats.view_count + 1,
        last_viewed_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_increment_view_on_playlist_add
    AFTER INSERT ON viday.content_to_playlist
    FOR EACH ROW
    EXECUTE FUNCTION increment_view_count();
