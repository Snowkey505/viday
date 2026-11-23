package com.vidayapi.service

import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.Role
import com.vidayapi.model.left
import com.vidayapi.model.right
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Date

/**
 * JWT-сервис приложения (ЛР5).
 *
 * Токены делятся по назначению:
 *  * `access` — обычный токен доступа (пускается JwtAuthenticationFilter);
 *  * `password_change` — короткоживущий токен, который выдаётся после успешной
 *    двухфакторной аутентификации, если пароль просрочен. Он принимается
 *    ТОЛЬКО эндпоинтом смены пароля и не даёт доступа к остальному API.
 */
@Service
class JwtService(
    jwtSecretProvider: JwtSecretProvider,
    @Value("\${jwt.expiration-ms}") private val expirationMs: Long,
    @Value("\${viday.auth.password-change-token-ttl-minutes:10}") passwordChangeTtlMinutes: Long,
) {
    private val key = Keys.hmacShaKeyFor(jwtSecretProvider.secret.toByteArray())
    private val passwordChangeTtlMs = passwordChangeTtlMinutes * 60_000L

    fun generateToken(userId: Int, username: String, role: Role): String =
        generate(userId, username, role.name, purpose = "access", ttlMs = expirationMs)

    fun generatePasswordChangeToken(userId: Int, username: String): String =
        generate(userId, username, role = null, purpose = "password_change", ttlMs = passwordChangeTtlMs)

    private fun generate(
        userId: Int,
        username: String,
        role: String?,
        purpose: String,
        ttlMs: Long,
    ): String {
        val now = Date()
        val builder = Jwts.builder()
            .setSubject(userId.toString())
            .claim("username", username)
            .claim("purpose", purpose)
            .setIssuedAt(now)
            .setExpiration(Date(now.time + ttlMs))
        role?.let { builder.claim("role", it) }
        return builder.signWith(key).compact()
    }

    fun validateAndGetUserId(token: String): Result<Int> =
        validate(token, expectedPurpose = "access")

    fun validatePasswordChangeToken(token: String): Result<Int> =
        validate(token, expectedPurpose = "password_change")

    private fun validate(token: String, expectedPurpose: String): Result<Int> {
        return try {
            val claims = parseClaims(token) ?: return Error.Unauthorized.left()
            if (claims.get("purpose", String::class.java) != expectedPurpose) {
                return Error.Unauthorized.left()
            }
            if (claims.expiration == null || claims.expiration.before(Date())) {
                return Error.Unauthorized.left()
            }
            claims.subject.toInt().right()
        } catch (e: Exception) {
            Error.Unauthorized.left()
        }
    }

    fun getUsernameFromToken(token: String): String? {
        return try {
            val claims = parseClaims(token) ?: return null
            if (claims.get("purpose", String::class.java) != "access") return null
            claims.get("username", String::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun getRoleFromToken(token: String): Role? {
        return try {
            val claims = parseClaims(token) ?: return null
            if (claims.get("purpose", String::class.java) != "access") return null
            val roleName = claims.get("role", String::class.java)
            Role.valueOf(roleName)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseClaims(token: String): Claims? =
        Jwts.parserBuilder()
            .setSigningKey(key)
            .build()
            .parseClaimsJws(token)
            .body
}
