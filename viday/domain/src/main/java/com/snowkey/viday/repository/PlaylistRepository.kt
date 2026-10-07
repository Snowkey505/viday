package com.snowkey.viday.repository

import com.snowkey.viday.model.PlaylistResult

interface PlaylistRepository {
    suspend fun createPlaylist(name: String, accessType: String): PlaylistResult
    suspend fun getPlaylists(): PlaylistResult
    suspend fun getPlaylistById(playlistId: Long): PlaylistResult
    suspend fun updatePlaylist(playlistId: Long, name: String?, accessType: String?): PlaylistResult
    suspend fun deletePlaylist(playlistId: Long): PlaylistResult
    suspend fun addContentToPlaylist(
        playlistId: Long,
        contentId: Long,
        position: Int?
    ): PlaylistResult

    suspend fun removeContentFromPlaylist(playlistId: Long, contentId: Long): PlaylistResult
    suspend fun getAvailablePlaylists(): PlaylistResult
}
