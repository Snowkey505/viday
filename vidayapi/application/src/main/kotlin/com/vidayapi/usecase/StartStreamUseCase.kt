package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.cache.CacheKeys
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.Stream
import com.vidayapi.model.StreamStatus
import com.vidayapi.model.StreamWithContent
import com.vidayapi.port.CachePort
import com.vidayapi.port.StreamRepository
import java.time.OffsetDateTime
import java.util.UUID

class StartStreamUseCase(
    private val streamRepository: StreamRepository,
    private val cachePort: CachePort,
) {
    fun execute(contentId: Int, ownerId: Int): Result<StreamWithContent> {
        return streamRepository.findByContentId(contentId).flatMap { existing ->
            if (existing.content.ownerId != ownerId) {
                return Error.Forbidden.left()
            }
            when (existing.stream.status) {
                StreamStatus.LIVE -> return Error.StreamAlreadyLive.left()
                StreamStatus.ENDED -> return Error.ValidationFailed("Stream already ended").left()
                else -> Unit
            }

            streamRepository.hasActiveLiveStream(ownerId).flatMap { hasLive ->
                if (hasLive) {
                    Error.StreamAlreadyLive.left()
                } else {
                    val streamKey = UUID.randomUUID().toString().replace("-", "")
                    val updated = Stream(
                        contentId = contentId,
                        status = StreamStatus.LIVE,
                        scheduledAt = existing.stream.scheduledAt,
                        startedAt = OffsetDateTime.now(),
                        endedAt = null,
                        streamKey = streamKey,
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
    }
}
