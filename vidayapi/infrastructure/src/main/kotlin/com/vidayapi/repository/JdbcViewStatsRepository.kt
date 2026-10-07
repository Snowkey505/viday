package com.vidayapi.repository

import com.vidayapi.model.ContentViewStatsEntry
import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.port.ViewStatsRepository
import org.springframework.dao.DataAccessException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
class JdbcViewStatsRepository(private val jdbcTemplate: JdbcTemplate) : ViewStatsRepository {

    private val rowMapper = RowMapper { rs, _ ->
        ContentViewStatsEntry(
            contentId = rs.getInt("content_id"),
            contentName = rs.getString("content_name"),
            ownerId = rs.getInt("owner_id"),
            viewCount = rs.getLong("view_count"),
            lastViewedAt = rs.getObject("last_viewed_at", OffsetDateTime::class.java),
        )
    }

    private val baseSelect = """
        SELECT s.content_id, c.name AS content_name, c.owner_id, s.view_count, s.last_viewed_at
        FROM viday.content_view_stats s
        JOIN viday.content c ON c.id = s.content_id
    """.trimIndent()

    override fun findAll(): Result<List<ContentViewStatsEntry>> {
        val sql = "$baseSelect ORDER BY s.view_count DESC, s.content_id"
        return try {
            jdbcTemplate.query(sql, rowMapper).right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findByContentId(contentId: Int): Result<ContentViewStatsEntry> {
        val sql = "$baseSelect WHERE s.content_id = ?"
        return try {
            val row = jdbcTemplate.queryForObject(sql, rowMapper, contentId)
            row?.right() ?: Error.NotFound.left()
        } catch (e: EmptyResultDataAccessException) {
            Error.NotFound.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findTopByViewCount(limit: Int): Result<List<ContentViewStatsEntry>> {
        val sql = "$baseSelect ORDER BY s.view_count DESC LIMIT ?"
        return try {
            jdbcTemplate.query(sql, rowMapper, limit).right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }
}
