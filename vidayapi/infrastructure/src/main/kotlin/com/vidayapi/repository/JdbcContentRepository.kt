package com.vidayapi.repository

import com.vidayapi.model.AccessType
import com.vidayapi.model.Codec
import com.vidayapi.model.Content
import com.vidayapi.model.ContentType
import com.vidayapi.model.MediaVariant
import com.vidayapi.model.Error
import com.vidayapi.model.Page
import com.vidayapi.model.PageRequest
import com.vidayapi.model.Result
import com.vidayapi.model.Video
import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.port.ContentRepository
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
class JdbcContentRepository(private val jdbcTemplate: JdbcTemplate) : ContentRepository {

    private val contentRowMapper = RowMapper { rs, _ ->
        Content(
            id = rs.getInt("id"),
            type = ContentType.valueOf(rs.getString("type_name")),
            name = rs.getString("name"),
            description = rs.getString("description"),
            source = rs.getString("source"),
            ownerId = rs.getInt("owner_id"),
            accessType = AccessType.valueOf(rs.getString("access_type_name")),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java)
        )
    }
    
    private val videoRowMapper = RowMapper { rs, _ ->
        Video(
            content = Content(
                id = rs.getInt("id"),
                type = ContentType.valueOf(rs.getString("type_name")),
                name = rs.getString("name"),
                description = rs.getString("description"),
                source = rs.getString("source_path") ?: rs.getString("source"),
                ownerId = rs.getInt("owner_id"),
                accessType = AccessType.valueOf(rs.getString("access_type_name")),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            ),
            durationSeconds = rs.getInt("duration_seconds"),
            preview = rs.getString("preview"),
        )
    }

    private val mediaVariantRowMapper = RowMapper { rs, _ ->
        MediaVariant(
            id = rs.getInt("id"),
            contentId = rs.getInt("content_id"),
            width = rs.getInt("width"),
            height = rs.getInt("height"),
            bitrate = rs.getInt("bitrate"),
            codec = Codec.valueOf(rs.getString("codec_name").replace(".", "")),
            isSource = rs.getBoolean("is_source"),
            filePath = rs.getString("file_path"),
            fileSizeBytes = rs.getObject("file_size_bytes") as? Long,
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java)
        )
    }

    override fun findById(id: Int): Result<Content> {
        val sql = """
            SELECT c.*, ct.name AS type_name, at.name AS access_type_name
            FROM viday.content c
            JOIN viday.content_type ct ON c.content_type_id = ct.id
            JOIN viday.access_type at ON c.access_type_id = at.id
            WHERE c.id = ?
        """.trimIndent()
        
        return try {
            val content = jdbcTemplate.queryForObject(sql, contentRowMapper, id)
            content?.right() ?: Error.NotFound.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun save(content: Content): Result<Content> {
        val sql = """
            INSERT INTO viday.content (content_type_id, name, description, source, owner_id, access_type_id, created_at)
            SELECT ct.id, ?, ?, ?, ?, at.id, ?
            FROM viday.content_type ct, viday.access_type at
            WHERE ct.name = ? AND at.name = ?
            RETURNING id
        """.trimIndent()

        return try {
            val generatedId = jdbcTemplate.query(sql, { rs, _ -> rs.getInt("id") },
                content.name,
                content.description,
                content.source,
                content.ownerId,
                content.createdAt,
                content.type.name,
                content.accessType.name
            ).firstOrNull()

            if (generatedId == null) {
                return Error.ValidationFailed("Invalid content type or access type").left()
            }

            content.copy(id = generatedId).right()
        } catch (e: DataIntegrityViolationException) {
            Error.StorageFailure.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun saveVideo(video: Video): Result<Video> {
        val sql = """
            INSERT INTO viday.video (content_id, duration_seconds, preview)
            VALUES (?, ?, ?)
            ON CONFLICT (content_id) DO UPDATE 
            SET duration_seconds = EXCLUDED.duration_seconds,
                preview = EXCLUDED.preview
        """.trimIndent()

        return try {
            jdbcTemplate.update(sql, video.content.id, video.durationSeconds, video.preview)
            video.right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findVideoByContentId(contentId: Int): Result<Video> {
        val sql = """
            SELECT c.*, ct.name AS type_name, at.name AS access_type_name, v.duration_seconds, v.preview, mv.file_path AS source_path
            FROM viday.content c
            JOIN viday.content_type ct ON c.content_type_id = ct.id
            JOIN viday.access_type at ON c.access_type_id = at.id
            JOIN viday.video v ON v.content_id = c.id
            LEFT JOIN viday.media_variant mv ON mv.content_id = c.id AND mv.is_source = TRUE
            WHERE c.id = ?
        """.trimIndent()

        return try {
            val video = jdbcTemplate.queryForObject(sql, { rs, _ ->
                Video(
                    content = Content(
                        id = rs.getInt("id"),
                        type = ContentType.valueOf(rs.getString("type_name")),
                        name = rs.getString("name"),
                        description = rs.getString("description"),
                        source = rs.getString("source_path") ?: rs.getString("source"),
                        ownerId = rs.getInt("owner_id"),
                        accessType = AccessType.valueOf(rs.getString("access_type_name")),
                        createdAt = rs.getObject("created_at", OffsetDateTime::class.java)
                    ),
                    durationSeconds = rs.getInt("duration_seconds"),
                    preview = rs.getString("preview")
                )
            }, contentId)

            video?.right() ?: Error.NotFound.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun saveMediaVariant(variant: MediaVariant): Result<MediaVariant> {
        val sql = """
            INSERT INTO viday.media_variant (content_id, width, height, bitrate, codec_id, is_source, file_path, file_size_bytes, created_at)
            SELECT ?, ?, ?, ?, c.id, ?, ?, ?, ?
            FROM viday.codec c
            WHERE c.name = ?
            RETURNING id
        """.trimIndent()

        return try {
            val generatedId = jdbcTemplate.query(sql, { rs, _ -> rs.getInt("id") },
                variant.contentId,
                variant.width,
                variant.height,
                variant.bitrate,
                variant.isSource,
                variant.filePath,
                variant.fileSizeBytes,
                variant.createdAt,
                variant.codec.name
            ).firstOrNull()

            if (generatedId == null) {
                return Error.ValidationFailed("Invalid codec").left()
            }

            variant.copy(id = generatedId).right()
        } catch (e: DataIntegrityViolationException) {
            Error.StorageFailure.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findPublicVideosPage(pageRequest: PageRequest): Result<Page<Video>> {
        val countSql = """
            SELECT COUNT(*)
            FROM viday.content c
            JOIN viday.content_type ct ON c.content_type_id = ct.id AND ct.name = 'VIDEO'
            JOIN viday.access_type at ON c.access_type_id = at.id AND at.name = 'PUBLIC'
        """.trimIndent()
        val dataSql = """
            SELECT c.*, ct.name AS type_name, at.name AS access_type_name,
                   v.duration_seconds, v.preview, mv.file_path AS source_path
            FROM viday.content c
            JOIN viday.content_type ct ON c.content_type_id = ct.id AND ct.name = 'VIDEO'
            JOIN viday.access_type at ON c.access_type_id = at.id AND at.name = 'PUBLIC'
            JOIN viday.video v ON v.content_id = c.id
            LEFT JOIN viday.media_variant mv ON mv.content_id = c.id AND mv.is_source = TRUE
            ORDER BY c.created_at DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        return queryVideoPage(countSql, emptyArray(), dataSql, pageRequest)
    }

    override fun findVideosByOwnerIdPage(ownerId: Int, pageRequest: PageRequest): Result<Page<Video>> {
        val countSql = """
            SELECT COUNT(*)
            FROM viday.content c
            JOIN viday.content_type ct ON c.content_type_id = ct.id AND ct.name = 'VIDEO'
            WHERE c.owner_id = ?
        """.trimIndent()
        val dataSql = """
            SELECT c.*, ct.name AS type_name, at.name AS access_type_name,
                   v.duration_seconds, v.preview, mv.file_path AS source_path
            FROM viday.content c
            JOIN viday.content_type ct ON c.content_type_id = ct.id AND ct.name = 'VIDEO'
            JOIN viday.access_type at ON c.access_type_id = at.id
            JOIN viday.video v ON v.content_id = c.id
            LEFT JOIN viday.media_variant mv ON mv.content_id = c.id AND mv.is_source = TRUE
            WHERE c.owner_id = ?
            ORDER BY c.created_at DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        return queryVideoPage(countSql, arrayOf(ownerId), dataSql, pageRequest, ownerId)
    }

    override fun findVideosByPlaylistIdPage(playlistId: Int, pageRequest: PageRequest): Result<Page<Video>> {
        val countSql = """
            SELECT COUNT(*)
            FROM viday.content c
            JOIN viday.content_to_playlist cp ON cp.content_id = c.id
            JOIN viday.video v ON v.content_id = c.id
            WHERE cp.playlist_id = ?
        """.trimIndent()
        val dataSql = """
            SELECT c.*, ct.name AS type_name, at.name AS access_type_name,
                   v.duration_seconds, v.preview, mv.file_path AS source_path
            FROM viday.content c
            JOIN viday.content_type ct ON c.content_type_id = ct.id
            JOIN viday.access_type at ON c.access_type_id = at.id
            JOIN viday.video v ON v.content_id = c.id
            JOIN viday.content_to_playlist cp ON cp.content_id = c.id
            LEFT JOIN viday.media_variant mv ON mv.content_id = c.id AND mv.is_source = TRUE
            WHERE cp.playlist_id = ?
            ORDER BY cp.position ASC
            LIMIT ? OFFSET ?
        """.trimIndent()
        return queryVideoPage(countSql, arrayOf(playlistId), dataSql, pageRequest, playlistId)
    }

    private fun queryVideoPage(
        countSql: String,
        countArgs: Array<Any?>,
        dataSql: String,
        pageRequest: PageRequest,
        vararg dataFilterArgs: Any?,
    ): Result<Page<Video>> {
        return try {
            val total = jdbcTemplate.queryForObject(countSql, Long::class.java, *countArgs) ?: 0L
            val queryArgs = dataFilterArgs.toMutableList()
            queryArgs.add(pageRequest.size)
            queryArgs.add(pageRequest.offset)
            val items = jdbcTemplate.query(dataSql, videoRowMapper, *queryArgs.toTypedArray())
            Page(
                items = items,
                page = pageRequest.page,
                size = pageRequest.size,
                totalElements = total,
            ).right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findPublicContent(): Result<List<Content>> {
        val sql = """
            SELECT c.*, ct.name AS type_name, at.name AS access_type_name
            FROM viday.content c
            JOIN viday.content_type ct ON c.content_type_id = ct.id
            JOIN viday.access_type at ON c.access_type_id = at.id
            WHERE at.name = 'PUBLIC'
        """.trimIndent()
        
        return try {
            jdbcTemplate.query(sql, contentRowMapper).right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findByOwnerId(ownerId: Int): Result<List<Content>> {
        val sql = """
            SELECT c.*, ct.name AS type_name, at.name AS access_type_name
            FROM viday.content c
            JOIN viday.content_type ct ON c.content_type_id = ct.id
            JOIN viday.access_type at ON c.access_type_id = at.id
            WHERE c.owner_id = ?
        """.trimIndent()
        
        return try {
            jdbcTemplate.query(sql, contentRowMapper, ownerId).right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findByPlaylistId(playlistId: Int): Result<List<Content>> {
        val sql = """
            SELECT c.*, ct.name AS type_name, at.name AS access_type_name
            FROM viday.content c
            JOIN viday.content_type ct ON c.content_type_id = ct.id
            JOIN viday.access_type at ON c.access_type_id = at.id
            JOIN viday.content_to_playlist cp ON cp.content_id = c.id
            WHERE cp.playlist_id = ?
            ORDER BY cp.position ASC
        """.trimIndent()

        return try {
            jdbcTemplate.query(sql, contentRowMapper, playlistId).right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findMediaVariantsByContentId(contentId: Int): Result<List<MediaVariant>> {
        val sql = """
            SELECT mv.*, c.name AS codec_name
            FROM viday.media_variant mv
            JOIN viday.codec c ON mv.codec_id = c.id
            WHERE mv.content_id = ?
        """.trimIndent()
        
        return try {
            jdbcTemplate.query(sql, mediaVariantRowMapper, contentId).right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun deleteById(contentId: Int, ownerId: Int): Result<Unit> {
        val sql = "DELETE FROM viday.content WHERE id = ? AND owner_id = ?"
        return try {
            val rows = jdbcTemplate.update(sql, contentId, ownerId)
            if (rows > 0) Unit.right() else Error.NotVideoOwner.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun deleteByIdAsAdmin(contentId: Int): Result<Unit> {
        val sql = "DELETE FROM viday.content WHERE id = ?"
        return try {
            val rows = jdbcTemplate.update(sql, contentId)
            if (rows > 0) Unit.right() else Error.NotFound.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findSourceMediaVariant(contentId: Int): Result<MediaVariant> {
        val sql = """
            SELECT mv.*, c.name AS codec_name
            FROM viday.media_variant mv
            JOIN viday.codec c ON mv.codec_id = c.id
            WHERE mv.content_id = ? AND mv.is_source = TRUE
        """.trimIndent()
        
        return try {
            val variant = jdbcTemplate.queryForObject(sql, mediaVariantRowMapper, contentId)
            variant?.right() ?: Error.NotFound.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun updateVideo(video: Video): Result<Unit> {
        val sql = """
        UPDATE viday.content
        SET name = ?,
            description = ?,
            access_type_id = (SELECT id FROM viday.access_type WHERE name = ?)
        WHERE id = ? AND content_type_id = (SELECT id FROM viday.content_type WHERE name = 'VIDEO')
    """.trimIndent()

        return try {
            val rowsAffected = jdbcTemplate.update(
                sql,
                video.content.name,
                video.content.description,
                video.content.accessType.name,
                video.content.id
            )
            if (rowsAffected == 0) Error.NotFound.left() else Unit.right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }
}
