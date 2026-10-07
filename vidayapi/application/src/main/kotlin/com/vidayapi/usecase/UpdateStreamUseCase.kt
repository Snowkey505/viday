package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.cache.CacheKeys
import com.vidayapi.dto.requests.UpdateStreamRequest
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.StreamWithContent
import com.vidayapi.port.CachePort
import com.vidayapi.port.StreamRepository

class UpdateStreamUseCase(
    private val streamRepository: StreamRepository,
    private val cachePort: CachePort,
) {
    fun execute(contentId: Int, ownerId: Int, request: UpdateStreamRequest): Result<StreamWithContent> {
        if (request.name != null && request.name.isBlank()) {
            return Error.ValidationFailed("Stream name cannot be blank").left()
        }

        return streamRepository.findByContentId(contentId).flatMap { existing ->
            if (existing.content.ownerId != ownerId) {
                return Error.Forbidden.left()
            }

            val updated = existing.copy(
                content = existing.content.copy(
                    name = request.name ?: existing.content.name,
                    description = request.description ?: existing.content.description,
                    source = request.source ?: existing.content.source,
                    accessType = request.accessType ?: existing.content.accessType,
                ),
                stream = existing.stream.copy(
                    scheduledAt = request.scheduledAt ?: existing.stream.scheduledAt,
                ),
            )

            streamRepository.updateWithContent(updated).map {
                cachePort.deleteByPrefix(CacheKeys.LIVE_STREAMS_PREFIX)
                cachePort.deleteByPrefix(CacheKeys.videoPrefix(contentId))
                it
            }
        }
    }
}
