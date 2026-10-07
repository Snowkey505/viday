package com.vidayapi.repository

import com.vidayapi.model.AccessType
import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.model.Content
import com.vidayapi.model.ContentType
import com.vidayapi.model.Error
import com.vidayapi.model.Page
import com.vidayapi.model.PageRequest
import com.vidayapi.model.Result
import com.vidayapi.model.Stream
import com.vidayapi.model.StreamStatus
import com.vidayapi.model.StreamWithContent
import com.vidayapi.port.StreamRepository
import org.springframework.dao.DataAccessException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
class JdbcStreamRepository(private val jdbcTemplate: JdbcTemplate) : StreamRepository {

    private val streamWithContentMapper = RowMapper { rs, _ ->
        StreamWithContent(
            stream = Stream(
                contentId = rs.getInt("content_id"),
                status = StreamStatus.valueOf(rs.getString("stream_status_name")),
                scheduledAt = rs.getObject("scheduled_at", OffsetDateTime::class.java),
                startedAt = rs.getObject("started_at", OffsetDateTime::class.java),
                endedAt = rs.getObject("ended_at", OffsetDateTime::class.java),
                streamKey = rs.getString("stream_key"),
            ),
            content = Content(
                id = rs.getInt("content_id"),
                type = ContentType.STREAM,
                name = rs.getString("name"),
                description = rs.getString("description"),
                source = rs.getString("source"),
                ownerId = rs.getInt("owner_id"),
                accessType = AccessType.valueOf(rs.getString("access_type_name")),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            ),
        )
    }

    private val baseSelect = """
        SELECT c.id AS content_id, c.name, c.description, c.source, c.owner_id, c.created_at,
               at.name AS access_type_name, ss.name AS stream_status_name,
               s.scheduled_at, s.started_at, s.ended_at, s.stream_key
        FROM viday.stream s
        JOIN viday.content c ON c.id = s.content_id
        JOIN viday.content_type ct ON c.content_type_id = ct.id AND ct.name = 'STREAM'
        JOIN viday.access_type at ON c.access_type_id = at.id
        JOIN viday.stream_status ss ON s.stream_status_id = ss.id
    """.trimIndent()

    override fun save(
        stream: Stream,
        contentName: String,
        description: String?,
        source: String?,
        ownerId: Int,
        accessTypeName: String,
    ): Result<StreamWithContent> {
        val insertContent = """
            INSERT INTO viday.content (content_type_id, name, description, source, owner_id, access_type_id, created_at)
            SELECT ct.id, ?, ?, ?, ?, at.id, ?
            FROM viday.content_type ct, viday.access_type at
            WHERE ct.name = 'STREAM' AND at.name = ?
            RETURNING id
        """.trimIndent()

        return try {
            val contentId = jdbcTemplate.query(insertContent, { rs, _ -> rs.getInt("id") },
                contentName, description, source, ownerId, OffsetDateTime.now(), accessTypeName,
            ).firstOrNull() ?: return Error.ValidationFailed("Invalid access type").left()

            val insertStream = """
                INSERT INTO viday.stream (content_id, stream_status_id, scheduled_at, started_at, ended_at, stream_key)
                SELECT ?, ss.id, ?, NULL, NULL, NULL
                FROM viday.stream_status ss
                WHERE ss.name = ?
            """.trimIndent()
            jdbcTemplate.update(
                insertStream,
                contentId,
                stream.scheduledAt,
                stream.status.name,
            )

            findByContentId(contentId)
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findByContentId(contentId: Int): Result<StreamWithContent> {
        val sql = "$baseSelect WHERE c.id = ?"
        return try {
            val row = jdbcTemplate.queryForObject(sql, streamWithContentMapper, contentId)
            row?.right() ?: Error.StreamNotFound.left()
        } catch (e: EmptyResultDataAccessException) {
            Error.StreamNotFound.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findByOwnerId(ownerId: Int): Result<List<StreamWithContent>> {
        val sql = "$baseSelect WHERE c.owner_id = ? ORDER BY c.created_at DESC"
        return try {
            jdbcTemplate.query(sql, streamWithContentMapper, ownerId).right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findLiveStreams(): Result<List<StreamWithContent>> {
        val sql = "$baseSelect WHERE ss.name = 'LIVE' ORDER BY s.started_at DESC"
        return try {
            jdbcTemplate.query(sql, streamWithContentMapper).right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findLiveStreamsPage(pageRequest: PageRequest): Result<Page<StreamWithContent>> {
        val countSql = """
            SELECT COUNT(*)
            FROM viday.stream s
            JOIN viday.stream_status ss ON s.stream_status_id = ss.id AND ss.name = 'LIVE'
        """.trimIndent()
        val dataSql = """
            $baseSelect
            WHERE ss.name = 'LIVE'
            ORDER BY s.started_at DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        return try {
            val total = jdbcTemplate.queryForObject(countSql, Long::class.java) ?: 0L
            val items = jdbcTemplate.query(
                dataSql,
                streamWithContentMapper,
                pageRequest.size,
                pageRequest.offset,
            )
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

    override fun findByStreamKey(streamKey: String): Result<StreamWithContent> {
        val sql = "$baseSelect WHERE s.stream_key = ?"
        return try {
            val row = jdbcTemplate.queryForObject(sql, streamWithContentMapper, streamKey)
            row?.right() ?: Error.StreamNotFound.left()
        } catch (e: EmptyResultDataAccessException) {
            Error.StreamNotFound.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun update(stream: Stream): Result<Stream> {
        val sql = """
            UPDATE viday.stream s
            SET stream_status_id = ss.id,
                scheduled_at = ?,
                started_at = ?,
                ended_at = ?,
                stream_key = ?
            FROM viday.stream_status ss
            WHERE s.content_id = ? AND ss.name = ?
        """.trimIndent()
        return try {
            val rows = jdbcTemplate.update(
                sql,
                stream.scheduledAt,
                stream.startedAt,
                stream.endedAt,
                stream.streamKey,
                stream.contentId,
                stream.status.name,
            )
            if (rows == 0) Error.StreamNotFound.left() else stream.right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun updateWithContent(streamWithContent: StreamWithContent): Result<StreamWithContent> {
        val content = streamWithContent.content
        val stream = streamWithContent.stream
        val updateContentSql = """
            UPDATE viday.content
            SET name = ?,
                description = ?,
                source = ?,
                access_type_id = (SELECT id FROM viday.access_type WHERE name = ?)
            WHERE id = ?
              AND content_type_id = (SELECT id FROM viday.content_type WHERE name = 'STREAM')
        """.trimIndent()
        val updateStreamSql = """
            UPDATE viday.stream s
            SET scheduled_at = ?
            FROM viday.stream_status ss
            WHERE s.content_id = ? AND ss.id = s.stream_status_id
        """.trimIndent()

        return try {
            val contentRows = jdbcTemplate.update(
                updateContentSql,
                content.name,
                content.description,
                content.source,
                content.accessType.name,
                content.id,
            )
            if (contentRows == 0) {
                return Error.StreamNotFound.left()
            }
            jdbcTemplate.update(updateStreamSql, stream.scheduledAt, stream.contentId)
            findByContentId(stream.contentId)
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun delete(contentId: Int, ownerId: Int): Result<Unit> {
        val sql = "DELETE FROM viday.content WHERE id = ? AND owner_id = ?"
        return try {
            val rows = jdbcTemplate.update(sql, contentId, ownerId)
            if (rows > 0) Unit.right() else Error.Forbidden.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun hasActiveLiveStream(ownerId: Int): Result<Boolean> {
        val sql = """
            SELECT EXISTS(
                SELECT 1 FROM viday.stream s
                JOIN viday.content c ON c.id = s.content_id
                JOIN viday.stream_status ss ON ss.id = s.stream_status_id
                WHERE c.owner_id = ? AND ss.name = 'LIVE'
            )
        """.trimIndent()
        return try {
            jdbcTemplate.queryForObject(sql, Boolean::class.java, ownerId)?.right() ?: false.right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }
}
