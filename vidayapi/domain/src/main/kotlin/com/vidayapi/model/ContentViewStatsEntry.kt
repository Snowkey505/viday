package com.vidayapi.model

import java.time.OffsetDateTime

data class ContentViewStatsEntry(
    val contentId: Int,
    val contentName: String,
    val ownerId: Int,
    val viewCount: Long,
    val lastViewedAt: OffsetDateTime,
)
