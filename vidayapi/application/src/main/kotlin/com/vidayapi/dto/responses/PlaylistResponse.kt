package com.vidayapi.dto.responses

import com.vidayapi.model.AccessType
import java.time.LocalDateTime

data class PlaylistResponse(
    val id: Long,
    val name: String,
    val ownerId: Long,
    val ownerName: String,
    val accessType: AccessType,
    val contentIds: List<Long>,
    val createdAt: LocalDateTime
)
