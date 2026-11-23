package com.vidayapi.controller

import com.vidayapi.dto.requests.AddContentToPlaylistRequest
import com.vidayapi.dto.requests.CreatePlaylistRequest
import com.vidayapi.dto.requests.UpdatePlaylistRequest
import com.vidayapi.dto.responses.PageResponse
import com.vidayapi.dto.responses.PlaylistResponse
import com.vidayapi.model.AccessType
import com.vidayapi.model.PageRequest
import com.vidayapi.model.Error
import com.vidayapi.model.Playlist
import com.vidayapi.model.Result
import com.vidayapi.model.left
import com.vidayapi.port.PlaylistRepository
import com.vidayapi.usecase.AddContentToPlaylistUseCase
import com.vidayapi.usecase.CreatePlaylistUseCase
import com.vidayapi.usecase.UpdatePlaylistUseCase
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
import java.time.ZoneOffset

@RestController
@RequestMapping("/api/playlists")
class PlaylistController(
    private val createPlaylistUseCase: CreatePlaylistUseCase,
    private val updatePlaylistUseCase: UpdatePlaylistUseCase,
    private val addContentToPlaylistUseCase: AddContentToPlaylistUseCase,
    private val playlistRepository: PlaylistRepository
) {
    @PostMapping
    fun createPlaylist(
        @RequestBody request: CreatePlaylistRequest,
        @AuthenticationPrincipal userDetails: UserDetails?
    ): ResponseEntity<Any> {
        val userId = userDetails?.username?.toIntOrNull()
            ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

        return createPlaylistUseCase.execute(
            name = request.name,
            ownerId = userId,
            accessType = request.accessType
        ).fold(
            onSuccess = { playlist -> ResponseEntity.status(201).body(playlist.toResponse()) },
            onFailure = { error ->
                when (error) {
                    is Error.ValidationFailed ->
                        ResponseEntity.badRequest().body(mapOf("error" to "Invalid playlist name"))
                    is Error.Unauthorized -> ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))
                    else -> ResponseEntity.status(500).body(mapOf("error" to "Failed to create playlist"))
                }
            }
        )
    }

    @GetMapping
    fun getPlaylists(
        @AuthenticationPrincipal userDetails: UserDetails?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<Any> {
        val pageRequest = PageRequest(page, size).normalized()

        val result = if (userDetails?.username != null) {
            val userId = userDetails.username.toIntOrNull()
                ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))
            playlistRepository.findPlaylistsByOwnerIdPage(userId, pageRequest)
        } else {
            playlistRepository.findPublicPlaylistsPage(pageRequest)
        }

        return result.fold(
            onSuccess = { page ->
                ResponseEntity.ok(PageResponse.from(page.map { it.toResponse() }))
            },
            onFailure = { ResponseEntity.status(500).body(mapOf("error" to "Failed to fetch playlists")) }
        )
    }

    @GetMapping("/{playlistId}")
    fun getPlaylistById(@PathVariable playlistId: Int): ResponseEntity<Any> =
        playlistRepository.findById(playlistId).fold(
            onSuccess = { playlist -> ResponseEntity.ok(playlist.toResponse()) },
            onFailure = { error ->
                when (error) {
                    is Error.NotFound -> ResponseEntity.notFound().build()
                    else -> ResponseEntity.status(500).body(mapOf("error" to "Failed to fetch playlist"))
                }
            }
        )

    @PostMapping("/{playlistId}/contents")
    fun addContentToPlaylist(
        @PathVariable playlistId: Int,
        @RequestBody request: AddContentToPlaylistRequest,
        @AuthenticationPrincipal userDetails: UserDetails?
    ): ResponseEntity<Any> {
        if (userDetails == null) {
            return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))
        }
        val ownerId = userDetails.username.toIntOrNull()
            ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

        val position = if (request.position == -1) null else request.position
        return addContentToPlaylistUseCase.execute(
            ownerId = ownerId,
            playlistId = playlistId,
            contentId = request.contentId.toInt(),
            position = position,
        ).fold(
            onSuccess = { ResponseEntity.ok(mapOf("message" to "Content added successfully")) },
            onFailure = { error ->
                when (error) {
                    Error.PlaylistPositionConflict ->
                        ResponseEntity.status(409).body(mapOf("error" to "Content position conflict"))
                    Error.StorageFailure ->
                        ResponseEntity.status(500).body(mapOf("error" to "Failed to add content"))
                    else -> playlistAccessErrorResponse(error as Error)
                }
            }
        )
    }

    @DeleteMapping("/{playlistId}")
    fun deletePlaylist(
        @PathVariable playlistId: Int,
        @AuthenticationPrincipal userDetails: UserDetails?
    ): ResponseEntity<Any> {
        if (userDetails == null) {
            return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))
        }
        val ownerId = userDetails.username?.toIntOrNull()
            ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

        return playlistRepository.deletePlaylist(playlistId, ownerId).fold(
            onSuccess = { ResponseEntity.noContent().build() },
            onFailure = { error -> playlistAccessErrorResponse(error as Error) }
        )
    }

    @DeleteMapping("/{playlistId}/contents/{contentId}")
    fun removeContentFromPlaylist(
        @PathVariable playlistId: Int,
        @PathVariable contentId: Int,
        @AuthenticationPrincipal userDetails: UserDetails?
    ): ResponseEntity<Any> {
        if (userDetails == null) {
            return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))
        }
        val ownerId = userDetails.username?.toIntOrNull()
            ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

        return ensurePlaylistOwner(playlistId, ownerId).fold(
            onSuccess = {
                playlistRepository.removeContentFromPlaylist(
                    contentId = contentId,
                    playlistId = playlistId
                ).fold(
                    onSuccess = { ResponseEntity.noContent().build() },
                    onFailure = { error ->
                        when (error) {
                            is Error.NotFound -> ResponseEntity.notFound().build()
                            else -> ResponseEntity.status(500).body(mapOf("error" to "Failed to remove content"))
                        }
                    }
                )
            },
            onFailure = { error -> playlistAccessErrorResponse(error as Error) }
        )
    }

    private fun ensurePlaylistOwner(playlistId: Int, userId: Int): Result<Playlist> =
        playlistRepository.findById(playlistId).fold(
            onSuccess = { playlist ->
                if (playlist.ownerId != userId) {
                    Error.Forbidden.left()
                } else {
                    Result.success(playlist)
                }
            },
            onFailure = { Result.failure(it) }
        )

    private fun playlistAccessErrorResponse(err: Error): ResponseEntity<Any> =
        when (err) {
            is Error.NotFound -> ResponseEntity.notFound().build()
            is Error.Forbidden ->
                ResponseEntity.status(403).body(mapOf("error" to "Forbidden"))

            else -> ResponseEntity.status(500).body(mapOf("error" to "Request failed"))
        }

    private fun Playlist.toResponse(): PlaylistResponse {
        return PlaylistResponse(
            id = id?.toLong() ?: 0L,
            name = name,
            ownerId = ownerId.toLong(),
            ownerName = "",
            accessType = accessType,
            contentIds = emptyList(),
            createdAt = createdAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
        )
    }

    @GetMapping("/available")
    fun getAvailablePlaylists(
        @AuthenticationPrincipal userDetails: UserDetails?
    ): ResponseEntity<Any> {
        val userId = userDetails?.username?.toIntOrNull()

        return playlistRepository.findAvailablePlaylists(userId).fold(
            onSuccess = { playlists -> ResponseEntity.ok(playlists.map { it.toResponse() }) },
            onFailure = { ResponseEntity.status(500).body(mapOf("error" to "Failed to fetch playlists")) }
        )
    }

    @PutMapping("/{id}")
    fun updatePlaylist(
        @PathVariable id: Int,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) accessType: AccessType?,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val userId = userDetails?.username?.toIntOrNull()
            ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

        val request = UpdatePlaylistRequest(
            name = name,
            accessType = accessType,
        )

        return updatePlaylistUseCase.execute(id, userId, request).fold(
            onSuccess = { ResponseEntity.ok(it) },
            onFailure = { error ->
                when (error) {
                    is Error.NotFound -> ResponseEntity.notFound().build()
                    is Error.Forbidden ->
                        ResponseEntity.status(403).body(mapOf("error" to "You are not the owner of this playlist"))
                    is Error.AlreadyExists ->
                        ResponseEntity.status(409).body(mapOf("error" to "You already have a playlist with this name"))
                    is Error.StorageFailure ->
                        ResponseEntity.internalServerError().body(mapOf("error" to "Failed to update playlist"))
                    else ->
                        ResponseEntity.internalServerError().body(mapOf("error" to "Failed to update playlist"))
                }
            }
        )
    }
}
