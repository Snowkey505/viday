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

@Service
class JwtService(
    @Value("\${jwt.secret}") private val secret: String,
    @Value("\${jwt.expiration-ms}") private val expirationMs: Long
) {

    private val key = Keys.hmacShaKeyFor(secret.toByteArray())

    fun generateToken(userId: Int, username: String, role: Role): String {
        val now = Date()
        val expiryDate = Date(now.time + expirationMs)

        return Jwts.builder()
            .setSubject(userId.toString())
            .claim("username", username)
            .claim("role", role.name)
            .setIssuedAt(now)
            .setExpiration(expiryDate)
            .signWith(key)
            .compact()
    }

    fun validateAndGetUserId(token: String): Result<Int> {
        return try {
            val claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .body

            if (claims.expiration.before(Date())) {
                return Error.Unauthorized.left()
            }

            claims.subject.toInt().right()
        } catch (e: Exception) {
            Error.Unauthorized.left()
        }
    }

    fun getUsernameFromToken(token: String): String? {
        return try {
            val claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .body
            claims.get("username", String::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun getRoleFromToken(token: String): Role? {
        return try {
            val claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .body
            val roleName = claims.get("role", String::class.java)
            Role.valueOf(roleName)
        } catch (e: Exception) {
            null
        }
    }
}
