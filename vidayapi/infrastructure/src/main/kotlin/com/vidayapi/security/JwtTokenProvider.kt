package com.vidayapi.security

import com.vidayapi.model.Role
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date

@Component
class JwtTokenProvider(
    @Value("\${jwt.secret:404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}") private val jwtSecret: String,
    @Value("\${jwt.expiration-ms:86400000}") private val jwtExpirationMs: Long
) {

    private val key = Keys.hmacShaKeyFor(jwtSecret.toByteArray())

    fun generateToken(userId: Long, username: String, role: Role): String {
        val now = Date()
        val expiryDate = Date(now.time + jwtExpirationMs)

        return Jwts.builder()
            .setSubject(userId.toString())
            .claim("username", username)
            .claim("role", role.name)
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
            
            claims.subject.toLong()
        } catch (e: Exception) {
            null
        }
    }

    fun validateToken(token: String): Boolean {
        return try {
            Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
            true
        } catch (e: Exception) {
            false
        }
    }
}
