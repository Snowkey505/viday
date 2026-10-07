package com.vidayapi.dto.responses

data class UserResponse(
    val id: Long,
    val username: String,
    val role: String,
    val token: String? = null
)
