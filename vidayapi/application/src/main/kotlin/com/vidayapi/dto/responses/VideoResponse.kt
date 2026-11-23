package com.vidayapi.dto.responses

data class VideoResponse(
    val id: Int,
    val name: String,
    val description: String?,
    val source: String,
    val preview: String,
    val durationSeconds: Int,
    val accessType: String,
    val ownerId: Int
)
