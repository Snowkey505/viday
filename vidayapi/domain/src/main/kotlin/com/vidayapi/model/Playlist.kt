package com.vidayapi.model

import java.time.OffsetDateTime

data class Playlist(
    val id: Int? = null,
    val name: String,
    val ownerId: Int,
    val accessType: AccessType,
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)
