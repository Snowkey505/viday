package com.vidayapi.security

import com.vidayapi.model.Role
import com.vidayapi.service.JwtSecretProvider
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date

@Component
class JwtTokenProvider(
    jwtSecretProvider: JwtSecretProvider,
    @Value("\${jwt.expiration-ms:86400000}") private val jwtExpirationMs: Long,
) {

    private val key = Keys.hmacShaKeyFor(jwtSecretProvider.secret.toByteArray())

    fun generateToken(userId: Long, username: String, role: Role): String {
        val now = Date()
        val expiryDate = Date(now.time + jwtExpirationMs)

        return Jwts.builder()
            .setSubject(userId.toString())
            .claim("username", username)
            .claim("role", role.name)
            .claim("purpose", "access")
            .setIssuedAt(now)
            .setExpiration(expiryDate)
            .signWith(key)
            .compact()
    }

    fun getUserIdFromToken(token: String): Long? {
        return try {
            val claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .body

            if (claims.get("purpose", String::class.java) != "access") {
                return null
            }
            claims.subject.toLong()
        } catch (e: Exception) {
            null
        }
    }

    fun validateToken(token: String): Boolean {
        return try {
            val claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .body
            claims.get("purpose", String::class.java) == "access" &&
                claims.expiration != null &&
                claims.expiration.after(Date())
        } catch (e: Exception) {
            false
        }
    }
}
