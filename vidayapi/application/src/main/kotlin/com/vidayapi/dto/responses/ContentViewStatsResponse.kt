package com.vidayapi.dto.responses

import com.vidayapi.model.ContentViewStatsEntry
import java.time.OffsetDateTime

data class ContentViewStatsResponse(
    val contentId: Int,
    val contentName: String,
    val ownerId: Int,
    val viewCount: Long,
    val lastViewedAt: OffsetDateTime,
) {
    companion object {
        fun from(entry: ContentViewStatsEntry) = ContentViewStatsResponse(
            contentId = entry.contentId,
            contentName = entry.contentName,
            ownerId = entry.ownerId,
            viewCount = entry.viewCount,
            lastViewedAt = entry.lastViewedAt,
        )
    }
}
