package com.snowkey.viday.usecase.playlist

import com.snowkey.viday.model.PlaylistResult
import com.snowkey.viday.repository.PlaylistRepository

class GetPlaylistsUseCase(
    private val playlistRepository: PlaylistRepository
) {
    suspend operator fun invoke(): PlaylistResult {
        return playlistRepository.getPlaylists()
    }
}
