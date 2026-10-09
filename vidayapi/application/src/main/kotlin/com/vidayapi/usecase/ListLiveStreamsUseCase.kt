package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.right
import com.vidayapi.cache.CacheKeys
import com.vidayapi.cache.CacheTtl
import com.vidayapi.dto.responses.PageResponse
import com.vidayapi.dto.responses.StreamResponse
import com.vidayapi.model.PageRequest
import com.vidayapi.model.Result
import com.vidayapi.port.CachePort
import com.vidayapi.port.StreamRepository
import tools.jackson.databind.ObjectMapper

class ListLiveStreamsUseCase(
    private val streamRepository: StreamRepository,
    private val cachePort: CachePort,
    private val objectMapper: ObjectMapper,
) {
    fun execute(page: Int, size: Int): Result<PageResponse<StreamResponse>> {
        val pageRequest = PageRequest(page, size).normalized()
        val cacheKey = CacheKeys.liveStreams(pageRequest.page, pageRequest.size)

        cachePort.get(cacheKey)?.let { cached ->
            return readPage(cached).right()
        }

        return streamRepository.findLiveStreamsPage(pageRequest).flatMap { streamPage ->
            val response = PageResponse.from(streamPage.map { StreamResponse.from(it) })
            cachePort.set(
                cacheKey,
                objectMapper.writeValueAsString(response),
                CacheTtl.LIVE_STREAMS_SEC,
            )
            response.right()
        }
    }

    private fun readPage(json: String): PageResponse<StreamResponse> {
        val type = objectMapper.typeFactory.constructParametricType(
            PageResponse::class.java,
            StreamResponse::class.java,
        )
        return objectMapper.readValue(json, type)
    }
}
