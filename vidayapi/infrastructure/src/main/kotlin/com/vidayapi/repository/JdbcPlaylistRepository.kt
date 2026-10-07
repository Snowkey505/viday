package com.vidayapi.repository

import com.vidayapi.model.AccessType
import com.vidayapi.model.Error
import com.vidayapi.model.Page
import com.vidayapi.model.PageRequest
import com.vidayapi.model.Result
import com.vidayapi.model.Playlist
import com.vidayapi.model.PlaylistItem
import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.port.PlaylistRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
class JdbcPlaylistRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val logger: Logger = LoggerFactory.getLogger(JdbcPlaylistRepository::class.java),
) : PlaylistRepository {

    private val playlistRowMapper = RowMapper { rs, _ ->
        Playlist(
            id = rs.getInt("id"),
            name = rs.getString("name"),
            ownerId = rs.getInt("owner_id"),
            accessType = AccessType.valueOf(rs.getString("access_type_name")),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java)
        )
    }

    override fun findById(id: Int): Result<Playlist> {
        val sql = """
            SELECT p.id, p.name, p.owner_id, p.created_at, at.name AS access_type_name
            FROM viday.playlist p
            JOIN viday.access_type at ON p.access_type_id = at.id
            WHERE p.id = ?
        """.trimIndent()
        return try {
            val playlist = jdbcTemplate.queryForObject(sql, playlistRowMapper, id)
            playlist?.right() ?: Error.NotFound.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun save(playlist: Playlist): Result<Playlist> {
        val sql = """
            INSERT INTO viday.playlist (name, owner_id, access_type_id, created_at)
            SELECT ?, ?, id, ?
            FROM viday.access_type
            WHERE name = ?
            RETURNING id
        """.trimIndent()

        return try {
            val generatedId = jdbcTemplate.query(sql, { rs, _ -> rs.getInt("id") },
                playlist.name,
                playlist.ownerId,
                playlist.createdAt,
                playlist.accessType.name
            ).firstOrNull()

            if (generatedId == null) {
                logger.warn("Access type '{}' not found in viday.access_type", playlist.accessType.name)
                return Error.ValidationFailed("").left()
            }

            playlist.copy(id = generatedId).right()
        } catch (e: DataIntegrityViolationException) {
            logger.error("DB Integrity Violation {}", e.rootCause?.message ?: e.message)
            Error.StorageFailure.left()
        } catch (e: DataAccessException) {
            logger.error("DB Error {}: {}", e.javaClass.simpleName, e.rootCause?.message ?: e.message)
            Error.StorageFailure.left()
        }
    }

    override fun addContentToPlaylist(contentId: Int, playlistId: Int, position: Int?): Result<PlaylistItem> {
        val finalPosition = if (position == null || position == -1) {
            countItemsInPlaylist(playlistId).fold(
                onSuccess = { it + 1 },
                onFailure = { return Result.failure(it) }
            )
        } else {
            position
        }

        val sql = """
        INSERT INTO viday.content_to_playlist (content_id, playlist_id, position, added_at)
        VALUES (?, ?, ?, ?)
    """.trimIndent()

        return try {
            jdbcTemplate.update(sql, contentId, playlistId, finalPosition, OffsetDateTime.now())
            PlaylistItem(contentId, playlistId, finalPosition).right()
        } catch (e: DuplicateKeyException) {
            Error.PlaylistPositionConflict.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun countItemsInPlaylist(playlistId: Int): Result<Int> {
        val sql = "SELECT COUNT(*) FROM viday.content_to_playlist WHERE playlist_id = ?"
        return try {
            val count = jdbcTemplate.queryForObject(sql, Int::class.java, playlistId) ?: 0
            count.right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findByOwnerId(ownerId: Int): Result<List<Playlist>> {
        val sql = """
            SELECT p.id, p.name, p.owner_id, p.created_at, at.name AS access_type_name
            FROM viday.playlist p
            JOIN viday.access_type at ON p.access_type_id = at.id
            WHERE p.owner_id = ?
        """.trimIndent()
        return try {
            val list = jdbcTemplate.query(sql, playlistRowMapper, ownerId)
            list.right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findPlaylistsByOwnerIdPage(ownerId: Int, pageRequest: PageRequest): Result<Page<Playlist>> {
        val countSql = "SELECT COUNT(*) FROM viday.playlist WHERE owner_id = ?"
        val dataSql = """
            SELECT p.id, p.name, p.owner_id, p.created_at, at.name AS access_type_name
            FROM viday.playlist p
            JOIN viday.access_type at ON p.access_type_id = at.id
            WHERE p.owner_id = ?
            ORDER BY p.created_at DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        return queryPlaylistPage(countSql, arrayOf(ownerId), dataSql, pageRequest, ownerId)
    }

    override fun findPublicPlaylistsPage(pageRequest: PageRequest): Result<Page<Playlist>> {
        val countSql = """
            SELECT COUNT(*)
            FROM viday.playlist p
            JOIN viday.access_type at ON p.access_type_id = at.id
            WHERE at.name = 'PUBLIC'
        """.trimIndent()
        val dataSql = """
            SELECT p.id, p.name, p.owner_id, p.created_at, at.name AS access_type_name
            FROM viday.playlist p
            JOIN viday.access_type at ON p.access_type_id = at.id
            WHERE at.name = 'PUBLIC'
            ORDER BY p.created_at DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        return queryPlaylistPage(countSql, emptyArray(), dataSql, pageRequest)
    }

    private fun queryPlaylistPage(
        countSql: String,
        countArgs: Array<Any?>,
        dataSql: String,
        pageRequest: PageRequest,
        vararg dataFilterArgs: Any?,
    ): Result<Page<Playlist>> {
        return try {
            val total = jdbcTemplate.queryForObject(countSql, Long::class.java, *countArgs) ?: 0L
            val queryArgs = dataFilterArgs.toMutableList()
            queryArgs.add(pageRequest.size)
            queryArgs.add(pageRequest.offset)
            val items = jdbcTemplate.query(dataSql, playlistRowMapper, *queryArgs.toTypedArray())
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

    override fun findPublicPlaylists(): Result<List<Playlist>> {
        val sql = """
            SELECT p.id, p.name, p.owner_id, p.created_at, at.name AS access_type_name
            FROM viday.playlist p
            JOIN viday.access_type at ON p.access_type_id = at.id
            WHERE at.name = 'PUBLIC'
        """.trimIndent()
        return try {
            val list = jdbcTemplate.query(sql, playlistRowMapper)
            list.right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun existsByNameAndOwnerId(name: String, ownerId: Int): Result<Boolean> {
        val sql = """
            SELECT COUNT(*) FROM viday.playlist 
            WHERE name = ? AND owner_id = ?
        """.trimIndent()
        return try {
            val count = jdbcTemplate.queryForObject(sql, Int::class.java, name, ownerId) ?: 0
            (count > 0).right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun deletePlaylist(playlistId: Int, ownerId: Int): Result<Unit> =
        findById(playlistId).fold(
            onSuccess = { playlist ->
                if (playlist.ownerId != ownerId) {
                    Error.Forbidden.left()
                } else {
                    try {
                        val sql = "DELETE FROM viday.playlist WHERE id = ? AND owner_id = ?"
                        val rows = jdbcTemplate.update(sql, playlistId, ownerId)
                        if (rows > 0) Unit.right() else Error.NotFound.left()
                    } catch (e: DataAccessException) {
                        Error.StorageFailure.left()
                    }
                }
            },
            onFailure = { Result.failure(it) }
        )

    override fun removeContentFromPlaylist(contentId: Int, playlistId: Int): Result<Unit> {
        val sql =
            "DELETE FROM viday.content_to_playlist WHERE content_id = ? AND playlist_id = ?"
        return try {
            val rows = jdbcTemplate.update(sql, contentId, playlistId)
            if (rows > 0) Unit.right() else Error.NotFound.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun deleteByIdAsAdmin(playlistId: Int): Result<Unit> {
        val sql = "DELETE FROM viday.playlist WHERE id = ?"
        return try {
            val rows = jdbcTemplate.update(sql, playlistId)
            if (rows > 0) Unit.right() else Error.NotFound.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findAvailablePlaylists(currentUserId: Int?): Result<List<Playlist>> {
        val sql = if (currentUserId != null) {
            """
            SELECT DISTINCT p.id, p.name, p.owner_id, p.created_at, at.name AS access_type_name
            FROM viday.playlist p
            JOIN viday.access_type at ON p.access_type_id = at.id
            WHERE 
                -- 1. Свои плейлисты
                p.owner_id = ?
                
                UNION
                
                -- 2. Публичные плейлисты других пользователей
                SELECT p.id, p.name, p.owner_id, p.created_at, at.name AS access_type_name
                FROM viday.playlist p
                JOIN viday.access_type at ON p.access_type_id = at.id
                WHERE at.name = 'PUBLIC' AND p.owner_id != ?
                
                UNION
                
                -- 3. Плейлисты с доступом для подписчиков (если текущий пользователь подписан)
                SELECT p.id, p.name, p.owner_id, p.created_at, at.name AS access_type_name
                FROM viday.playlist p
                JOIN viday.access_type at ON p.access_type_id = at.id
                INNER JOIN viday.user_follows uf ON uf.followed_user_id = p.owner_id
                WHERE at.name = 'SUBSCRIBERS_ONLY' 
                  AND uf.following_user_id = ?
                  AND p.owner_id != ?
        """.trimIndent()
        } else {
            """
            -- Для неавторизованных пользователей только публичные плейлисты
            SELECT p.id, p.name, p.owner_id, p.created_at, at.name AS access_type_name
            FROM viday.playlist p
            JOIN viday.access_type at ON p.access_type_id = at.id
            WHERE at.name = 'PUBLIC'
        """.trimIndent()
        }

        return try {
            val playlists = if (currentUserId != null) {
                jdbcTemplate.query(sql, playlistRowMapper, currentUserId, currentUserId, currentUserId, currentUserId)
            } else {
                jdbcTemplate.query(sql, playlistRowMapper)
            }
            playlists.right()
        } catch (e: DataAccessException) {
            logger.error("DB Error {}: {}", e.javaClass.simpleName, e.rootCause?.message ?: e.message)
            Error.StorageFailure.left()
        }
    }

    override fun updatePlaylist(playlist: Playlist): Result<Playlist> {
        val existingPlaylist = findById(playlist.id!!).fold(
            onSuccess = { it },
            onFailure = { return Error.NotFound.left() }
        )

        if (existingPlaylist.ownerId != playlist.ownerId) {
            return Error.Forbidden.left()
        }

        val sql = """
        UPDATE viday.playlist 
        SET name = ?, 
            access_type_id = (SELECT id FROM viday.access_type WHERE name = ?)
        WHERE id = ? AND owner_id = ?
    """.trimIndent()

        return try {
            val rowsAffected = jdbcTemplate.update(
                sql,
                playlist.name,
                playlist.accessType.name,
                playlist.id,
                playlist.ownerId
            )
            if (rowsAffected == 0) Error.NotFound.left() else playlist.right()
        } catch (e: DuplicateKeyException) {
            Error.AlreadyExists.left()
        } catch (e: DataAccessException) {
            logger.error("DB Error {}: {}", e.javaClass.simpleName, e.rootCause?.message ?: e.message)
            Error.StorageFailure.left()
        }
    }
}
