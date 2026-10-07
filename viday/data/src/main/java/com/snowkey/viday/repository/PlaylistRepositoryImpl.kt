package com.snowkey.viday.repository

import com.snowkey.viday.api.ApiService
import com.snowkey.viday.api.TokenManager
import com.snowkey.viday.api.requests.AddContentToPlaylistRequest
import com.snowkey.viday.api.requests.CreatePlaylistRequest
import com.snowkey.viday.api.requests.UpdatePlaylistRequest
import com.snowkey.viday.api.responses.PlaylistResponse
import com.snowkey.viday.model.Playlist
import com.snowkey.viday.model.PlaylistError
import com.snowkey.viday.model.PlaylistResult
import retrofit2.HttpException
import java.io.IOException

class PlaylistRepositoryImpl(
    private val apiService: ApiService,
    private val tokenManager: TokenManager
) : PlaylistRepository {

    override suspend fun createPlaylist(name: String, accessType: String): PlaylistResult {
        val token =
            tokenManager.getToken() ?: return PlaylistResult(error = PlaylistError.ACCESS_ERROR)

        return try {
            val response = apiService.createPlaylist(
                token = "Bearer $token",
                request = CreatePlaylistRequest(name, accessType)
            )

            val playlist = Playlist(
                id = response.id.toLong(),
                name = response.name,
                accessType = response.accessType,
                ownerId = response.ownerId.toLong()
            )

            PlaylistResult(error = PlaylistError.OK, playlists = listOf(playlist))
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> PlaylistResult(error = PlaylistError.ACCESS_ERROR)
                403 -> PlaylistResult(error = PlaylistError.FORBIDDEN)
                400 -> PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
                else -> PlaylistResult(error = PlaylistError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            PlaylistResult(error = PlaylistError.NETWORK_ERROR)
        } catch (e: Exception) {
            PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
        }
    }

    override suspend fun getPlaylistById(playlistId: Long): PlaylistResult {
        val token = tokenManager.getToken()

        return try {
            val response = apiService.getPlaylistById(
                playlistId = playlistId,
                authorization = token?.let { "Bearer $it" }
            )

            val playlist = Playlist(
                id = response.id.toLong(),
                name = response.name,
                accessType = response.accessType,
                ownerId = response.ownerId.toLong()
            )

            PlaylistResult(error = PlaylistError.OK, playlists = listOf(playlist))
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> PlaylistResult(error = PlaylistError.ACCESS_ERROR)
                403 -> PlaylistResult(error = PlaylistError.FORBIDDEN)
                404 -> PlaylistResult(error = PlaylistError.NOT_FOUND)
                else -> PlaylistResult(error = PlaylistError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            PlaylistResult(error = PlaylistError.NETWORK_ERROR)
        } catch (e: Exception) {
            PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
        }
    }

    override suspend fun updatePlaylist(
        playlistId: Long,
        name: String?,
        accessType: String?
    ): PlaylistResult {
        val token = tokenManager.getToken()
        if (token == null) {
            return PlaylistResult(error = PlaylistError.ACCESS_ERROR)
        }

        return try {
            val response = apiService.updatePlaylist(
                playlistId = playlistId,
                token = "Bearer $token",
                request = UpdatePlaylistRequest(name, accessType)
            )

            val playlist = Playlist(
                id = response.id.toLong(),
                name = response.name,
                accessType = response.accessType,
                ownerId = response.ownerId.toLong()
            )

            PlaylistResult(error = PlaylistError.OK, playlists = listOf(playlist))
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> PlaylistResult(error = PlaylistError.ACCESS_ERROR)
                403 -> PlaylistResult(error = PlaylistError.FORBIDDEN)
                404 -> PlaylistResult(error = PlaylistError.NOT_FOUND)
                else -> PlaylistResult(error = PlaylistError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            PlaylistResult(error = PlaylistError.NETWORK_ERROR)
        } catch (e: Exception) {
            PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
        }
    }

    override suspend fun deletePlaylist(playlistId: Long): PlaylistResult {
        val token = tokenManager.getToken()
        if (token == null) {
            return PlaylistResult(error = PlaylistError.ACCESS_ERROR)
        }

        return try {
            apiService.deletePlaylist(
                playlistId = playlistId,
                token = "Bearer $token"
            )
            PlaylistResult(error = PlaylistError.OK)
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> PlaylistResult(error = PlaylistError.ACCESS_ERROR)
                403 -> PlaylistResult(error = PlaylistError.FORBIDDEN)
                404 -> PlaylistResult(error = PlaylistError.NOT_FOUND)
                else -> PlaylistResult(error = PlaylistError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            PlaylistResult(error = PlaylistError.NETWORK_ERROR)
        } catch (e: Exception) {
            PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
        }
    }

    override suspend fun addContentToPlaylist(
        playlistId: Long,
        contentId: Long,
        position: Int?
    ): PlaylistResult {
        val token = tokenManager.getToken()
        if (token == null) {
            return PlaylistResult(error = PlaylistError.ACCESS_ERROR)
        }

        return try {
            apiService.addContentToPlaylist(
                playlistId = playlistId,
                token = "Bearer $token",
                request = AddContentToPlaylistRequest(contentId, position)
            )
            PlaylistResult(error = PlaylistError.OK)
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> PlaylistResult(error = PlaylistError.ACCESS_ERROR)
                403 -> PlaylistResult(error = PlaylistError.FORBIDDEN)
                404 -> PlaylistResult(error = PlaylistError.NOT_FOUND)
                409 -> PlaylistResult(error = PlaylistError.CONTENT_ALREADY_EXISTS)
                else -> PlaylistResult(error = PlaylistError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            PlaylistResult(error = PlaylistError.NETWORK_ERROR)
        } catch (e: Exception) {
            PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
        }
    }

    override suspend fun removeContentFromPlaylist(
        playlistId: Long,
        contentId: Long
    ): PlaylistResult {
        val token = tokenManager.getToken()
        if (token == null) {
            return PlaylistResult(error = PlaylistError.ACCESS_ERROR)
        }

        return try {
            apiService.removeContentFromPlaylist(
                playlistId = playlistId,
                contentId = contentId,
                token = "Bearer $token"
            )
            PlaylistResult(error = PlaylistError.OK)
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> PlaylistResult(error = PlaylistError.ACCESS_ERROR)
                403 -> PlaylistResult(error = PlaylistError.FORBIDDEN)
                404 -> PlaylistResult(error = PlaylistError.NOT_FOUND)
                else -> PlaylistResult(error = PlaylistError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            PlaylistResult(error = PlaylistError.NETWORK_ERROR)
        } catch (e: Exception) {
            PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
        }
    }

    override suspend fun getPlaylists(): PlaylistResult {
        val token = tokenManager.getToken()
        return try {
            val response = apiService.getPlaylists(
                authorization = token?.let { "Bearer $it" }
            )
            val playlists = response.items.map { it.toPlaylist() }
            PlaylistResult(error = PlaylistError.OK, playlists = playlists)
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> PlaylistResult(error = PlaylistError.ACCESS_ERROR)
                403 -> PlaylistResult(error = PlaylistError.FORBIDDEN)
                else -> PlaylistResult(error = PlaylistError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            PlaylistResult(error = PlaylistError.NETWORK_ERROR)
        } catch (e: Exception) {
            PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
        }
    }

    override suspend fun getAvailablePlaylists(): PlaylistResult {
        val token = tokenManager.getToken()
        return try {
            val response = apiService.getAvailablePlaylists(
                authorization = token?.let { "Bearer $it" }
            )
            val playlists = response.map { it.toPlaylist() }
            PlaylistResult(error = PlaylistError.OK, playlists = playlists)
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> PlaylistResult(error = PlaylistError.ACCESS_ERROR)
                403 -> PlaylistResult(error = PlaylistError.FORBIDDEN)
                404 -> PlaylistResult(error = PlaylistError.OK, playlists = emptyList())
                else -> PlaylistResult(error = PlaylistError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            PlaylistResult(error = PlaylistError.NETWORK_ERROR)
        } catch (e: Exception) {
            PlaylistResult(error = PlaylistError.UNKNOWN_ERROR)
        }
    }

    private fun PlaylistResponse.toPlaylist(): Playlist {
        return Playlist(
            id = id,
            name = name,
            accessType = accessType,
            ownerId = ownerId
        )
    }
}
