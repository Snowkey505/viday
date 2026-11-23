package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.right
import com.vidayapi.cache.CacheKeys
import com.vidayapi.cache.CacheTtl
import com.vidayapi.dto.responses.PageResponse
import com.vidayapi.dto.responses.VideoResponse
import com.vidayapi.model.PageRequest
import com.vidayapi.model.Result
import com.vidayapi.port.CachePort
import com.vidayapi.port.ContentRepository
import com.vidayapi.service.VideoResponseMapper
import tools.jackson.databind.ObjectMapper

class ListVideosUseCase(
    private val contentRepository: ContentRepository,
    private val cachePort: CachePort,
    private val videoResponseMapper: VideoResponseMapper,
    private val objectMapper: ObjectMapper,
) {
    fun execute(
        userId: Int?,
        playlistId: Int?,
        page: Int,
        size: Int,
    ): Result<PageResponse<VideoResponse>> {
        val pageRequest = PageRequest(page, size).normalized()
        val isPublicFeed = userId == null && playlistId == null

        if (isPublicFeed) {
            val cacheKey = CacheKeys.publicVideos(pageRequest.page, pageRequest.size)
            cachePort.get(cacheKey)?.let { cached ->
                return readPage(cached).right()
            }
        }

        val dataResult = when {
            playlistId != null ->
                contentRepository.findVideosByPlaylistIdPage(playlistId, pageRequest)
            userId != null ->
                contentRepository.findVideosByOwnerIdPage(userId, pageRequest)
            else ->
                contentRepository.findPublicVideosPage(pageRequest)
        }

        return dataResult.flatMap { videoPage ->
            val response = PageResponse.from(
                videoPage.map { videoResponseMapper.toResponse(it) },
            )
            if (isPublicFeed) {
                val cacheKey = CacheKeys.publicVideos(pageRequest.page, pageRequest.size)
                cachePort.set(
                    cacheKey,
                    objectMapper.writeValueAsString(response),
                    CacheTtl.PUBLIC_VIDEOS_SEC,
                )
            }
            response.right()
        }
    }

    private fun readPage(json: String): PageResponse<VideoResponse> {
        val type = objectMapper.typeFactory.constructParametricType(
            PageResponse::class.java,
            VideoResponse::class.java,
        )
        return objectMapper.readValue(json, type)
    }
}
