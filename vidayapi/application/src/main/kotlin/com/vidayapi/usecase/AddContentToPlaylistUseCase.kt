package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.Error
import com.vidayapi.model.PlaylistItem
import com.vidayapi.model.Result
import com.vidayapi.port.PlaylistRepository

class AddContentToPlaylistUseCase(
    private val playlistRepository: PlaylistRepository,
) {
    fun execute(
        ownerId: Int,
        playlistId: Int,
        contentId: Int,
        position: Int?,
    ): Result<PlaylistItem> {
        return playlistRepository.findById(playlistId).flatMap { playlist ->
            if (playlist.ownerId != ownerId) {
                Error.Forbidden.left()
            } else {
                playlistRepository.addContentToPlaylist(contentId, playlistId, position)
            }
        }
    }
}
