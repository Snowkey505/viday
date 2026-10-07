package com.vidayapi.model

import java.time.OffsetDateTime

data class User(
    val id: Int? = null,
    val username: String,
    val passwordHash: String,
    val role: Role,
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)

enum class Role {
    GUEST,
    USER,
    CREATOR,
    ADMIN,
    ANALYST
}
