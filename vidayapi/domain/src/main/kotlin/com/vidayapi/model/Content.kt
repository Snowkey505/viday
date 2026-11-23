package com.vidayapi.model

import java.time.OffsetDateTime

data class Content(
    val id: Int? = null,
    val type: ContentType,
    val name: String,
    val description: String?,
    val source: String?,
    val ownerId: Int,
    val accessType: AccessType,
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)

enum class ContentType {
    VIDEO,
    STREAM
}
