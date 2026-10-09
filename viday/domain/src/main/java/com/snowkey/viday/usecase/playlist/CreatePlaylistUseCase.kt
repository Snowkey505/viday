package com.snowkey.viday.usecase.playlist

import com.snowkey.viday.model.PlaylistError
import com.snowkey.viday.model.PlaylistResult
import com.snowkey.viday.repository.PlaylistRepository

class CreatePlaylistUseCase(
    private val playlistRepository: PlaylistRepository
) {
    suspend operator fun invoke(name: String, accessType: String): PlaylistResult {
        if (name.isBlank()) {
            return PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
        }
        return playlistRepository.createPlaylist(name, accessType)
    }
}
