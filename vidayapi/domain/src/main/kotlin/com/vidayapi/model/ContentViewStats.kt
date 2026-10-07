package com.vidayapi.model

import java.time.OffsetDateTime

data class ContentViewStats(
    val contentId: Int,
    var viewCount: Long = 0,
    var lastViewedAt: OffsetDateTime = OffsetDateTime.now()
)
