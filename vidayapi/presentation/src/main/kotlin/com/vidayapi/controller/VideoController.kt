package com.vidayapi.controller

import com.vidayapi.cache.CacheKeys
import com.vidayapi.cache.CacheTtl
import com.vidayapi.dto.requests.UpdateVideoRequest
import com.vidayapi.dto.requests.UploadVideoRequest
import com.vidayapi.dto.responses.VideoResponse
import com.vidayapi.model.AccessType
import com.vidayapi.model.Error
import com.vidayapi.port.CachePort
import com.vidayapi.port.ContentRepository
import com.vidayapi.service.VideoResponseMapper
import com.vidayapi.usecase.DeleteVideoUseCase
import com.vidayapi.usecase.ListVideosUseCase
import com.vidayapi.usecase.UpdateVideoUseCase
import com.vidayapi.usecase.UploadVideoUseCase
import com.vidayapi.security.RlsContextResolver
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/api/videos")
class VideoController(
    private val uploadVideoUseCase: UploadVideoUseCase,
    private val deleteVideoUseCase: DeleteVideoUseCase,
    private val updateVideoUseCase: UpdateVideoUseCase,
    private val listVideosUseCase: ListVideosUseCase,
    private val contentRepository: ContentRepository,
    private val videoResponseMapper: VideoResponseMapper,
    private val cachePort: CachePort,
    private val objectMapper: ObjectMapper,
) {
    @GetMapping
    fun getVideos(
        @RequestParam(required = false) userId: Int?,
        @RequestParam(required = false) playlistId: Int?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<Any> =
        listVideosUseCase.execute(userId, playlistId, page, size).fold(
            onSuccess = { ResponseEntity.ok(it) },
            onFailure = { error ->
                when (error) {
                    is Error.Unauthorized -> ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))
                    else -> ResponseEntity.status(500).body(mapOf("error" to "Failed to fetch videos"))
                }
            }
        )

    @DeleteMapping("/{id}")
    fun deleteVideo(
        @PathVariable id: Int,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val userId = userDetails?.username?.toIntOrNull()
            ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

        return deleteVideoUseCase.execute(id, userId).fold(
            onSuccess = { ResponseEntity.noContent().build() },
            onFailure = { error ->
                when (error) {
                    is Error.NotFound -> ResponseEntity.notFound().build()
                    is Error.NotVideoOwner ->
                        ResponseEntity.status(403).body(mapOf("error" to "You are not the owner of this video"))
                    else -> ResponseEntity.internalServerError().body(mapOf("error" to "Failed to delete video"))
                }
            }
        )
    }

    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadVideo(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("preview") preview: MultipartFile,
        @RequestParam("name") name: String,
        @RequestParam("description", required = false) description: String?,
        @RequestParam("accessType", defaultValue = "PUBLIC") accessType: AccessType,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        if (userDetails == null) {
            return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))
        }

        val request = UploadVideoRequest(
            name = name,
            description = description,
            accessType = accessType,
            file = file,
            preview = preview,
        )

        val userId = userDetails.username.toInt()
        return uploadVideoUseCase.execute(request, userId).fold(
            onSuccess = { video -> ResponseEntity.ok(videoResponseMapper.toResponse(video)) },
            onFailure = { error ->
                when (error) {
                    is Error.InvalidInput -> ResponseEntity.badRequest().body("Invalid video file")
                    is Error.StorageFailure -> ResponseEntity.internalServerError().body("Storage error occurred")
                    else -> ResponseEntity.internalServerError().body("Unknown error")
                }
            }
        )
    }

    @GetMapping("/{id}")
    fun getVideo(@PathVariable id: Int): ResponseEntity<Any> {
        val cacheKey = CacheKeys.video(id, RlsContextResolver.resolveCacheViewerKey())
        cachePort.get(cacheKey)?.let { cached ->
            return ResponseEntity.ok(objectMapper.readValue(cached, VideoResponse::class.java))
        }

        return contentRepository.findVideoByContentId(id).fold(
            onSuccess = { video ->
                val response = videoResponseMapper.toResponse(video)
                cachePort.set(cacheKey, objectMapper.writeValueAsString(response), CacheTtl.VIDEO_SEC)
                ResponseEntity.ok(response)
            },
            onFailure = { error ->
                when (error) {
                    is Error.NotFound -> ResponseEntity.notFound().build()
                    else -> ResponseEntity.internalServerError().body("Error occurred")
                }
            }
        )
    }

    @PutMapping("/{id}")
    fun updateVideo(
        @PathVariable id: Int,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) description: String?,
        @RequestParam(required = false, defaultValue = "PUBLIC") accessType: AccessType?,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val userId = userDetails?.username?.toIntOrNull()
            ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

        val request = UpdateVideoRequest(
            name = name,
            description = description,
            accessType = accessType,
        )

        return updateVideoUseCase.execute(id, userId, request).fold(
            onSuccess = { video ->
                cachePort.deleteByPrefix(CacheKeys.videoPrefix(id))
                ResponseEntity.ok(videoResponseMapper.toResponse(video))
            },
            onFailure = { error ->
                when (error) {
                    is Error.NotFound -> ResponseEntity.notFound().build()
                    is Error.NotVideoOwner ->
                        ResponseEntity.status(403).body(mapOf("error" to "You are not the owner of this video"))
                    is Error.InvalidInput ->
                        ResponseEntity.badRequest().body(mapOf("error" to "Invalid input data"))
                    else ->
                        ResponseEntity.internalServerError().body(mapOf("error" to "Failed to update video"))
                }
            }
        )
    }
}
