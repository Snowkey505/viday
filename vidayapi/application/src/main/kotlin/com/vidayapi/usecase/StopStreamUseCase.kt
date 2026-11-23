package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.cache.CacheKeys
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.Stream
import com.vidayapi.model.StreamStatus
import com.vidayapi.model.StreamWithContent
import com.vidayapi.port.CachePort
import com.vidayapi.port.StreamRepository
import java.time.OffsetDateTime

class StopStreamUseCase(
    private val streamRepository: StreamRepository,
    private val cachePort: CachePort,
) {
    fun execute(contentId: Int, ownerId: Int): Result<StreamWithContent> {
        return streamRepository.findByContentId(contentId).flatMap { existing ->
            if (existing.content.ownerId != ownerId) {
                return Error.Forbidden.left()
            }
            if (existing.stream.status != StreamStatus.LIVE) {
                return Error.StreamNotLive.left()
            }

            val updated = Stream(
                contentId = contentId,
                status = StreamStatus.ENDED,
                scheduledAt = existing.stream.scheduledAt,
                startedAt = existing.stream.startedAt,
                endedAt = OffsetDateTime.now(),
                streamKey = existing.stream.streamKey,
            )

            streamRepository.update(updated).flatMap {
                streamRepository.findByContentId(contentId).map {
                    cachePort.deleteByPrefix(CacheKeys.LIVE_STREAMS_PREFIX)
                    cachePort.deleteByPrefix(CacheKeys.videoPrefix(contentId))
                    it
                }
            }
        }
    }
}
