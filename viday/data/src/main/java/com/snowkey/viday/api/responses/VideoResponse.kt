package com.snowkey.viday.api.responses

data class VideoResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val source: String,
    val preview: String,
    val durationSeconds: Int,
    val accessType: String,
    val ownerId: Long
)
