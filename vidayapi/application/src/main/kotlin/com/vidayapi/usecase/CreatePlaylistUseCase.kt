package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.AccessType
import com.vidayapi.model.Error
import com.vidayapi.model.Playlist
import com.vidayapi.model.Result
import com.vidayapi.port.PlaylistRepository
import java.time.OffsetDateTime

class CreatePlaylistUseCase(
    private val playlistRepository: PlaylistRepository,
) {
    fun execute(name: String, ownerId: Int, accessType: AccessType): Result<Playlist> {
        if (name.isBlank()) {
            return Error.ValidationFailed("Playlist name cannot be blank").left()
        }

        return playlistRepository.existsByNameAndOwnerId(name, ownerId)
            .flatMap { exists ->
                if (exists) {
                    return Error.AlreadyExists.left()
                } else {
                    val newPlaylist = Playlist(
                        name = name,
                        ownerId = ownerId,
                        accessType = accessType,
                        createdAt = OffsetDateTime.now()
                    )
                    playlistRepository.save(newPlaylist)
                }
            }
    }
}
