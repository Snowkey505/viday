package com.snowkey.viday.usecase.playlist

import com.snowkey.viday.model.PlaylistError
import com.snowkey.viday.model.PlaylistResult
import com.snowkey.viday.repository.PlaylistRepository

class RemoveContentFromPlaylistUseCase(
    private val playlistRepository: PlaylistRepository
) {
    suspend operator fun invoke(playlistId: Long, contentId: Long): PlaylistResult {
        if (playlistId <= 0 || contentId <= 0) {
            return PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
        }
        return playlistRepository.removeContentFromPlaylist(playlistId, contentId)
    }
}
