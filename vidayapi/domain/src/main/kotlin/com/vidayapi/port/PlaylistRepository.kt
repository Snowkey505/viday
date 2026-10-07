package com.vidayapi.port

import com.vidayapi.model.Page
import com.vidayapi.model.PageRequest
import com.vidayapi.model.Playlist
import com.vidayapi.model.Result
import com.vidayapi.model.PlaylistItem

interface PlaylistRepository {
    fun findById(id: Int): Result<Playlist>
    fun findByOwnerId(ownerId: Int): Result<List<Playlist>>
    fun findPlaylistsByOwnerIdPage(ownerId: Int, pageRequest: PageRequest): Result<Page<Playlist>>
    fun findPublicPlaylists(): Result<List<Playlist>>
    fun findPublicPlaylistsPage(pageRequest: PageRequest): Result<Page<Playlist>>
    fun save(playlist: Playlist): Result<Playlist>
    fun addContentToPlaylist(contentId: Int, playlistId: Int, position: Int?): Result<PlaylistItem>
    fun countItemsInPlaylist(playlistId: Int): Result<Int>
    fun existsByNameAndOwnerId(name: String, ownerId: Int): Result<Boolean>
    fun deletePlaylist(playlistId: Int, ownerId: Int): Result<Unit>
    fun removeContentFromPlaylist(contentId: Int, playlistId: Int): Result<Unit>
    fun findAvailablePlaylists(currentUserId: Int?): Result<List<Playlist>>
    fun deleteByIdAsAdmin(playlistId: Int): Result<Unit>
    fun updatePlaylist(playlist: Playlist): Result<Playlist>
}
