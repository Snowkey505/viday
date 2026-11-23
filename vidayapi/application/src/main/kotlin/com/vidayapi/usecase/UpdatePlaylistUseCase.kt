package com.vidayapi.usecase

import com.vidayapi.dto.requests.UpdatePlaylistRequest
import com.vidayapi.model.Error
import com.vidayapi.model.Playlist
import com.vidayapi.model.Result
import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.port.PlaylistRepository
import org.springframework.stereotype.Component

@Component
class UpdatePlaylistUseCase(
    private val playlistRepository: PlaylistRepository,
) {
    fun execute(
        playlistId: Int,
        userId: Int,
        request: UpdatePlaylistRequest,
    ): Result<Playlist> {
        val existingPlaylist = playlistRepository.findById(playlistId).fold(
            onSuccess = { it },
            onFailure = { return Error.NotFound.left() }
        )

        if (existingPlaylist.ownerId != userId) {
            return Error.Forbidden.left()
        }

        val updatedName = request.name ?: existingPlaylist.name
        val updatedAccessType = request.accessType ?: existingPlaylist.accessType

        if (request.name != null && existingPlaylist.name != request.name) {
            val nameExists = playlistRepository.existsByNameAndOwnerId(updatedName, userId).fold(
                onSuccess = { it },
                onFailure = { return Error.StorageFailure.left() }
            )

            if (nameExists) {
                return Error.AlreadyExists.left()
            }
        }

        val updatedPlaylist = existingPlaylist.copy(
            name = updatedName,
            accessType = updatedAccessType
        )

        playlistRepository.updatePlaylist(updatedPlaylist).fold(
            onSuccess = { },
            onFailure = { error ->
                return when (error) {
                    is Error.AlreadyExists -> Error.AlreadyExists
                    else -> Error.StorageFailure
                }.left()
            }
        )

        return updatedPlaylist.right()
    }
}
