package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.cache.CacheKeys
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.StreamStatus
import com.vidayapi.port.CachePort
import com.vidayapi.port.StreamRepository

class DeleteStreamUseCase(
    private val streamRepository: StreamRepository,
    private val cachePort: CachePort,
) {
    fun execute(contentId: Int, ownerId: Int): Result<Unit> {
        return streamRepository.findByContentId(contentId).flatMap { existing ->
            if (existing.stream.status == StreamStatus.LIVE) {
                return Error.StreamAlreadyLive.left()
            }
            streamRepository.delete(contentId, ownerId).map {
                cachePort.deleteByPrefix(CacheKeys.LIVE_STREAMS_PREFIX)
                cachePort.deleteByPrefix(CacheKeys.videoPrefix(contentId))
            }
        }
    }
}
