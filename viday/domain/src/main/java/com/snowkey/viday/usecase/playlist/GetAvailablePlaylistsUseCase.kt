package com.snowkey.viday.usecase.playlist

import com.snowkey.viday.model.PlaylistResult
import com.snowkey.viday.repository.PlaylistRepository

class GetAvailablePlaylistsUseCase(private val playlistRepository: PlaylistRepository
) {
    suspend operator fun invoke(): PlaylistResult {
        return playlistRepository.getAvailablePlaylists()
    }
}
