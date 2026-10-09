package com.snowkey.viday.usecase.playlist

import com.snowkey.viday.model.PlaylistError
import com.snowkey.viday.model.PlaylistResult
import com.snowkey.viday.repository.PlaylistRepository

class AddContentToPlaylistUseCase(
    private val playlistRepository: PlaylistRepository
) {
    suspend operator fun invoke(playlistId: Long, contentId: Long, position: Int? = null): PlaylistResult {
        if (playlistId <= 0 || contentId <= 0) {
            return PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
        }
        return playlistRepository.addContentToPlaylist(playlistId, contentId, position)
    }
}
