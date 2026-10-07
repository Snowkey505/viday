package com.vidayapi.repository

import com.vidayapi.model.Error
import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.model.Result
import com.vidayapi.model.Role
import com.vidayapi.model.User
import com.vidayapi.port.UserRepository
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

@Repository
class JdbcUserRepository(private val jdbcTemplate: JdbcTemplate) : UserRepository {
    private val rowMapper = RowMapper { rs: ResultSet, _ ->
        User(
            id = rs.getInt("id"),
            username = rs.getString("username"),
            passwordHash = rs.getString("password"),
            role = Role.valueOf(rs.getString("role_name")),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java)
        )
    }

    override fun findById(id: Int): Result<User?> {
        val sql = """
        SELECT u.*, r.name as role_name 
        FROM viday."user" u 
        JOIN viday.role r ON u.role_id = r.id 
        WHERE u.id = ?
    """.trimIndent()
        return try {
            val user = jdbcTemplate.queryForObject(sql, rowMapper, id)
            user.right()
        } catch (e: EmptyResultDataAccessException) {
            null.right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun findByUsername(username: String): Result<User?> {
        val sql =
            "SELECT u.*, r.name as role_name FROM viday.\"user\" u JOIN viday.role r ON u.role_id = r.id WHERE u.username = ?"
        return try {
            val user = jdbcTemplate.queryForObject(sql, rowMapper, username)
            user.right()
        } catch (e: EmptyResultDataAccessException) {
            null.right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun save(user: User): Result<User> {
        val sql = """
            INSERT INTO viday."user" (username, password, role_id, created_at)
            VALUES (?, ?, (SELECT id FROM viday.role WHERE name = ?), ?)
            RETURNING id
        """.trimIndent()
        return try {
            val generatedId = jdbcTemplate.queryForObject(
                sql,
                Int::class.java,
                user.username,
                user.passwordHash,
                user.role.name,
                user.createdAt
            ) ?: return Error.StorageFailure.left()
            user.copy(id = generatedId).right()
        } catch (e: DuplicateKeyException) {
            Error.AlreadyExists.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun existsByUsername(username: String): Result<Boolean> {
        return findByUsername(username).map { it != null }
    }

    override fun update(user: User): Result<User> {
        val sql = """
            UPDATE viday."user" 
            SET username = ?, password = ?, role_id = (SELECT id FROM viday.role WHERE name = ?), created_at = ?
            WHERE id = ?
            RETURNING id
        """.trimIndent()
        return try {
            val generatedId = jdbcTemplate.queryForObject(
                sql,
                Int::class.java,
                user.username,
                user.passwordHash,
                user.role.name,
                user.createdAt,
                user.id
            ) ?: return Error.StorageFailure.left()
            user.copy(id = generatedId).right()
        } catch (e: DuplicateKeyException) {
            Error.AlreadyExists.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun follow(followingUserId: Int, followedUserId: Int): Result<Unit> {
        val sql =
            """INSERT INTO viday.user_follows (following_user_id, followed_user_id, created_at) 
               VALUES (?, ?, ?)""".trimIndent()
        return try {
            jdbcTemplate.update(sql, followingUserId, followedUserId, OffsetDateTime.now())
            Unit.right()
        } catch (e: DuplicateKeyException) {
            Error.UserAlreadyFollowed.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun unfollow(followingUserId: Int, followedUserId: Int): Result<Unit> {
        val sql =
            "DELETE FROM viday.user_follows WHERE following_user_id = ? AND followed_user_id = ?"
        return try {
            val rows = jdbcTemplate.update(sql, followingUserId, followedUserId)
            if (rows > 0) Unit.right() else Error.NotFound.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun deleteById(userId: Int): Result<Unit> {
        val sql = """DELETE FROM viday."user" WHERE id = ?"""
        return try {
            val rows = jdbcTemplate.update(sql, userId)
            if (rows > 0) Unit.right() else Error.NotFound.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }
}
