package com.vidayapi.model

import java.time.OffsetDateTime

data class Stream(
    val contentId: Int,
    val status: StreamStatus,
    val scheduledAt: OffsetDateTime? = null,
    val startedAt: OffsetDateTime? = null,
    val endedAt: OffsetDateTime? = null,
    val streamKey: String? = null
)

enum class StreamStatus {
    SCHEDULED, LIVE, ENDED, ERROR
}
