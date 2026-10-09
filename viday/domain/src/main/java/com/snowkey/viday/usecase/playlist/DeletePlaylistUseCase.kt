package com.snowkey.viday.usecase.playlist

import com.snowkey.viday.model.PlaylistError
import com.snowkey.viday.model.PlaylistResult
import com.snowkey.viday.repository.PlaylistRepository

class DeletePlaylistUseCase(
    private val playlistRepository: PlaylistRepository
) {
    suspend operator fun invoke(playlistId: Long): PlaylistResult {
        if (playlistId <= 0) {
            return PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
        }
        return playlistRepository.deletePlaylist(playlistId)
    }
}
