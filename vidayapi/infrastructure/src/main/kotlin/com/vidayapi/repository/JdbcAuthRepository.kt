package com.vidayapi.repository

import com.vidayapi.model.AccountLockState
import com.vidayapi.model.AuthAccount
import com.vidayapi.model.AuthChallenge
import com.vidayapi.model.AuthChallengePurpose
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.Role
import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.port.AuthRepository
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * JDBC-реализация [AuthRepository] (ЛР5).
 *
 * Все запросы идут через общий JdbcTemplate приложения. Для auth-эндпоинтов
 * RLS-фильтр переключает соединение на ограниченную роль viday_auth
 * (см. RlsContextResolver), поэтому анонимный логин не получает прав viday_admin.
 */
@Repository
class JdbcAuthRepository(private val jdbcTemplate: JdbcTemplate) : AuthRepository {

    private val accountMapper = RowMapper { rs: ResultSet, _ ->
        AuthAccount(
            userId = rs.getInt("id"),
            username = rs.getString("username"),
            passwordHash = rs.getString("password"),
            role = Role.valueOf(rs.getString("role_name")),
            email = rs.getString("email"),
            twoFactorEnabled = rs.getBoolean("two_factor_enabled"),
            failedLoginAttempts = rs.getInt("failed_login_attempts"),
            lockedUntil = rs.getObject("locked_until", OffsetDateTime::class.java),
            passwordChangedAt = rs.getObject("password_changed_at", OffsetDateTime::class.java),
        )
    }

    private val challengeMapper = RowMapper { rs: ResultSet, _ ->
        AuthChallenge(
            id = UUID.fromString(rs.getString("id")),
            userId = rs.getInt("user_id"),
            purpose = AuthChallengePurpose.valueOf(rs.getString("purpose")),
            codeHash = rs.getString("code_hash"),
            attempts = rs.getInt("attempts"),
            maxAttempts = rs.getInt("max_attempts"),
            expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java),
            consumedAt = rs.getObject("consumed_at", OffsetDateTime::class.java),
        )
    }

    override fun findAccountByUsername(username: String): Result<AuthAccount?> =
        queryAccount(
            """
            SELECT u.id, u.username, u.password, r.name AS role_name,
                   a.email, a.two_factor_enabled, a.failed_login_attempts,
                   a.locked_until, a.password_changed_at
            FROM viday."user" u
            JOIN viday.role r ON r.id = u.role_id
            JOIN viday.user_auth a ON a.user_id = u.id
            WHERE u.username = ?
            """.trimIndent(),
            username,
        )

    override fun findAccountByUserId(userId: Int): Result<AuthAccount?> =
        queryAccount(
            """
            SELECT u.id, u.username, u.password, r.name AS role_name,
                   a.email, a.two_factor_enabled, a.failed_login_attempts,
                   a.locked_until, a.password_changed_at
            FROM viday."user" u
            JOIN viday.role r ON r.id = u.role_id
            JOIN viday.user_auth a ON a.user_id = u.id
            WHERE u.id = ?
            """.trimIndent(),
            userId,
        )

    private fun queryAccount(sql: String, vararg args: Any): Result<AuthAccount?> =
        try {
            jdbcTemplate.query(sql, accountMapper, *args).firstOrNull().right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }

    override fun isEmailTaken(email: String): Result<Boolean> =
        try {
            val exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM viday.user_auth WHERE lower(email) = lower(?))",
                Boolean::class.java,
                email,
            ) ?: false
            exists.right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }

    override fun createAccount(
        userId: Int,
        email: String,
        passwordChangedAt: OffsetDateTime,
    ): Result<Unit> =
        try {
            jdbcTemplate.update(
                """
                INSERT INTO viday.user_auth
                    (user_id, email, two_factor_enabled, failed_login_attempts, password_changed_at)
                VALUES (?, ?, TRUE, 0, ?)
                """.trimIndent(),
                userId,
                email,
                passwordChangedAt,
            )
            Unit.right()
        } catch (e: DuplicateKeyException) {
            Error.AlreadyExists.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }

    @Transactional
    override fun updatePassword(
        userId: Int,
        passwordHash: String,
        passwordChangedAt: OffsetDateTime,
    ): Result<Unit> {
        return try {
            val updated = jdbcTemplate.update(
                "UPDATE viday.\"user\" SET password = ? WHERE id = ?",
                passwordHash,
                userId,
            )
            if (updated == 0) {
                return Error.NotFound.left()
            }
            jdbcTemplate.update(
                """
                UPDATE viday.user_auth
                SET password_changed_at = ?,
                    failed_login_attempts = 0,
                    locked_until = NULL
                WHERE user_id = ?
                """.trimIndent(),
                passwordChangedAt,
                userId,
            )
            Unit.right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun recordFailedLogin(
        userId: Int,
        maxAttempts: Int,
        lockDuration: Duration,
        now: OffsetDateTime,
    ): Result<AccountLockState> =
        try {
            val lockUntil = now.plus(lockDuration)
            val state = jdbcTemplate.query(
                """
                UPDATE viday.user_auth
                SET failed_login_attempts = failed_login_attempts + 1,
                    locked_until = CASE
                        WHEN failed_login_attempts + 1 >= ? THEN ?
                        ELSE locked_until
                    END
                WHERE user_id = ?
                RETURNING failed_login_attempts, locked_until
                """.trimIndent(),
                { rs, _ ->
                    AccountLockState(
                        failedAttempts = rs.getInt("failed_login_attempts"),
                        lockedUntil = rs.getObject("locked_until", OffsetDateTime::class.java),
                    )
                },
                maxAttempts,
                lockUntil,
                userId,
            ).firstOrNull()
            state?.right() ?: Error.NotFound.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }

    override fun resetLoginAttempts(userId: Int): Result<Unit> =
        try {
            jdbcTemplate.update(
                """
                UPDATE viday.user_auth
                SET failed_login_attempts = 0,
                    locked_until = NULL
                WHERE user_id = ?
                """.trimIndent(),
                userId,
            )
            Unit.right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }

    override fun createChallenge(challenge: AuthChallenge): Result<Unit> =
        try {
            jdbcTemplate.update(
                """
                INSERT INTO viday.auth_challenge
                    (id, user_id, purpose, code_hash, attempts, max_attempts, expires_at)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                challenge.id.toString(),
                challenge.userId,
                challenge.purpose.name,
                challenge.codeHash,
                challenge.attempts,
                challenge.maxAttempts,
                challenge.expiresAt,
            )
            Unit.right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }

    override fun findChallenge(id: UUID): Result<AuthChallenge?> =
        try {
            jdbcTemplate.query(
                "SELECT * FROM viday.auth_challenge WHERE id = ?::uuid",
                challengeMapper,
                id.toString(),
            ).firstOrNull().right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }

    override fun findLatestChallenge(
        userId: Int,
        purpose: AuthChallengePurpose,
    ): Result<AuthChallenge?> =
        try {
            jdbcTemplate.query(
                """
                SELECT * FROM viday.auth_challenge
                WHERE user_id = ? AND purpose = ? AND consumed_at IS NULL
                ORDER BY created_at DESC
                LIMIT 1
                """.trimIndent(),
                challengeMapper,
                userId,
                purpose.name,
            ).firstOrNull().right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }

    override fun incrementChallengeAttempts(id: UUID): Result<Int> {
        return try {
            val attempts = jdbcTemplate.queryForObject(
                """
                UPDATE viday.auth_challenge
                SET attempts = attempts + 1
                WHERE id = ?::uuid
                RETURNING attempts
                """.trimIndent(),
                Int::class.java,
                id.toString(),
            )
            if (attempts == null) Error.NotFound.left() else attempts.right()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
    }

    override fun consumeChallenge(id: UUID, consumedAt: OffsetDateTime): Result<Unit> =
        try {
            val updated = jdbcTemplate.update(
                "UPDATE viday.auth_challenge SET consumed_at = ? WHERE id = ?::uuid AND consumed_at IS NULL",
                consumedAt,
                id.toString(),
            )
            if (updated > 0) Unit.right() else Error.NotFound.left()
        } catch (e: DataAccessException) {
            Error.StorageFailure.left()
        }
}
