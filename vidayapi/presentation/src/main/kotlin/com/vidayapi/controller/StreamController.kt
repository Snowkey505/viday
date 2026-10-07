package com.vidayapi.controller

import com.vidayapi.dto.requests.CreateStreamRequest
import com.vidayapi.dto.requests.UpdateStreamRequest
import com.vidayapi.dto.responses.StreamResponse
import com.vidayapi.model.Error
import com.vidayapi.port.StreamRepository
import com.vidayapi.usecase.CreateStreamUseCase
import com.vidayapi.usecase.DeleteStreamUseCase
import com.vidayapi.usecase.ListLiveStreamsUseCase
import com.vidayapi.usecase.StartStreamUseCase
import com.vidayapi.usecase.StopStreamUseCase
import com.vidayapi.usecase.UpdateStreamUseCase
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/streams")
class StreamController(
    private val createStreamUseCase: CreateStreamUseCase,
    private val updateStreamUseCase: UpdateStreamUseCase,
    private val startStreamUseCase: StartStreamUseCase,
    private val stopStreamUseCase: StopStreamUseCase,
    private val deleteStreamUseCase: DeleteStreamUseCase,
    private val listLiveStreamsUseCase: ListLiveStreamsUseCase,
    private val streamRepository: StreamRepository,
) {
    @GetMapping("/live")
    fun listLiveStreams(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<Any> =
        listLiveStreamsUseCase.execute(page, size).fold(
            onSuccess = { ResponseEntity.ok(it) },
            onFailure = { ResponseEntity.internalServerError().body(mapOf("error" to "Failed to fetch live streams")) }
        )

    @GetMapping("/me")
    fun myStreams(@AuthenticationPrincipal userDetails: UserDetails?): ResponseEntity<Any> {
        val userId = userDetails?.username?.toIntOrNull()
            ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

        return streamRepository.findByOwnerId(userId).fold(
            onSuccess = { streams -> ResponseEntity.ok(streams.map { StreamResponse.from(it) }) },
            onFailure = { ResponseEntity.internalServerError().body(mapOf("error" to "Failed to fetch streams")) }
        )
    }

    @GetMapping("/{contentId}")
    fun getStream(@PathVariable contentId: Int): ResponseEntity<Any> =
        streamRepository.findByContentId(contentId).fold(
            onSuccess = { stream -> ResponseEntity.ok(StreamResponse.from(stream)) },
            onFailure = { error ->
                when (error) {
                    is Error.StreamNotFound -> ResponseEntity.notFound().build()
                    else -> ResponseEntity.internalServerError().body(mapOf("error" to "Failed to fetch stream"))
                }
            }
        )

    @PostMapping
    fun createStream(
        @RequestBody request: CreateStreamRequest,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val userId = userDetails?.username?.toIntOrNull()
            ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

        return createStreamUseCase.execute(
            name = request.name,
            description = request.description,
            source = request.source,
            ownerId = userId,
            accessType = request.accessType,
            scheduledAt = request.scheduledAt,
        ).fold(
            onSuccess = { stream -> ResponseEntity.status(201).body(StreamResponse.from(stream)) },
            onFailure = { error -> streamErrorResponse(error as Error) }
        )
    }

    @PutMapping("/{contentId}")
    fun updateStream(
        @PathVariable contentId: Int,
        @RequestBody request: UpdateStreamRequest,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val userId = userDetails?.username?.toIntOrNull()
            ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

        return updateStreamUseCase.execute(contentId, userId, request).fold(
            onSuccess = { stream -> ResponseEntity.ok(StreamResponse.from(stream)) },
            onFailure = { error -> streamErrorResponse(error as Error) }
        )
    }

    @PostMapping("/{contentId}/start")
    fun startStream(
        @PathVariable contentId: Int,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val userId = userDetails?.username?.toIntOrNull()
            ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

        return startStreamUseCase.execute(contentId, userId).fold(
            onSuccess = { stream -> ResponseEntity.ok(StreamResponse.from(stream)) },
            onFailure = { error -> streamErrorResponse(error as Error) }
        )
    }

    @PostMapping("/{contentId}/stop")
    fun stopStream(
        @PathVariable contentId: Int,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val userId = userDetails?.username?.toIntOrNull()
            ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

        return stopStreamUseCase.execute(contentId, userId).fold(
            onSuccess = { stream -> ResponseEntity.ok(StreamResponse.from(stream)) },
            onFailure = { error -> streamErrorResponse(error as Error) }
        )
    }

    @DeleteMapping("/{contentId}")
    fun deleteStream(
        @PathVariable contentId: Int,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val userId = userDetails?.username?.toIntOrNull()
            ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

        return deleteStreamUseCase.execute(contentId, userId).fold(
            onSuccess = { ResponseEntity.noContent().build() },
            onFailure = { error -> streamErrorResponse(error as Error) }
        )
    }

    private fun streamErrorResponse(error: Error): ResponseEntity<Any> =
        when (error) {
            is Error.StreamNotFound -> ResponseEntity.notFound().build()
            is Error.Forbidden -> ResponseEntity.status(403).body(mapOf("error" to "Forbidden"))
            is Error.StreamAlreadyLive ->
                ResponseEntity.status(409).body(mapOf("error" to "Stream is already live or another live stream exists"))
            is Error.StreamNotLive ->
                ResponseEntity.status(409).body(mapOf("error" to "Stream is not live"))
            is Error.ValidationFailed ->
                ResponseEntity.badRequest().body(mapOf("error" to error.details))
            else -> ResponseEntity.internalServerError().body(mapOf("error" to "Request failed"))
        }
}
