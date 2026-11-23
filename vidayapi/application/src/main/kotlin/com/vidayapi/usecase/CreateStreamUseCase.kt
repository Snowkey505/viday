package com.vidayapi.usecase

import com.vidayapi.model.left
import com.vidayapi.cache.CacheKeys
import com.vidayapi.model.AccessType
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.Stream
import com.vidayapi.model.StreamStatus
import com.vidayapi.model.StreamWithContent
import com.vidayapi.port.CachePort
import com.vidayapi.port.StreamRepository
import java.time.OffsetDateTime

class CreateStreamUseCase(
    private val streamRepository: StreamRepository,
    private val cachePort: CachePort,
) {
    fun execute(
        name: String,
        description: String?,
        source: String?,
        ownerId: Int,
        accessType: AccessType,
        scheduledAt: OffsetDateTime?,
    ): Result<StreamWithContent> {
        if (name.isBlank()) {
            return Error.ValidationFailed("Stream name cannot be blank").left()
        }

        val stream = Stream(
            contentId = 0,
            status = StreamStatus.SCHEDULED,
            scheduledAt = scheduledAt,
        )

        return streamRepository.save(stream, name, description, source, ownerId, accessType.name).map {
            cachePort.deleteByPrefix(CacheKeys.LIVE_STREAMS_PREFIX)
            it
        }
    }
}
