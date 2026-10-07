package com.vidayapi.dto.responses

import com.vidayapi.model.StreamWithContent
import java.time.OffsetDateTime

data class StreamResponse(
    val contentId: Int,
    val name: String,
    val description: String?,
    val source: String?,
    val ownerId: Int,
    val accessType: String,
    val status: String,
    val scheduledAt: OffsetDateTime?,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val streamKey: String?,
) {
    companion object {
        fun from(model: StreamWithContent) = StreamResponse(
            contentId = model.content.id ?: model.stream.contentId,
            name = model.content.name,
            description = model.content.description,
            source = model.content.source,
            ownerId = model.content.ownerId,
            accessType = model.content.accessType.name,
            status = model.stream.status.name,
            scheduledAt = model.stream.scheduledAt,
            startedAt = model.stream.startedAt,
            endedAt = model.stream.endedAt,
            streamKey = model.stream.streamKey,
        )
    }
}
