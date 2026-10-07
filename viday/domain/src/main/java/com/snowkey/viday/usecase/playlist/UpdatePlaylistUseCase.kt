package com.snowkey.viday.usecase.playlist

import com.snowkey.viday.model.PlaylistError
import com.snowkey.viday.model.PlaylistResult
import com.snowkey.viday.repository.PlaylistRepository

class UpdatePlaylistUseCase(
    private val playlistRepository: PlaylistRepository
) {
    suspend operator fun invoke(playlistId: Long, name: String?, accessType: String?): PlaylistResult {
        if (playlistId <= 0) {
            return PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
        }
        if (accessType != null && accessType !in listOf("PUBLIC", "PRIVATE", "FOLLOWERS")) {
            return PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
        }
        return playlistRepository.updatePlaylist(playlistId, name, accessType)
    }
}
