package com.snowkey.viday.presentation.playlistScreen

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snowkey.viday.mapper.toErrorMessageId
import com.snowkey.viday.mapper.toPlaylistView
import com.snowkey.viday.model.Playlist
import com.snowkey.viday.model.PlaylistError
import com.snowkey.viday.model.PlaylistView
import com.snowkey.viday.model.UserError
import com.snowkey.viday.usecase.playlist.AddContentToPlaylistUseCase
import com.snowkey.viday.usecase.playlist.CreatePlaylistUseCase
import com.snowkey.viday.usecase.playlist.DeletePlaylistUseCase
import com.snowkey.viday.usecase.playlist.GetAvailablePlaylistsUseCase
import com.snowkey.viday.usecase.playlist.GetPlaylistsUseCase
import com.snowkey.viday.usecase.playlist.RemoveContentFromPlaylistUseCase
import com.snowkey.viday.usecase.user.FollowCreatorUseCase
import com.snowkey.viday.usecase.user.GetUserByIdUseCase
import com.snowkey.viday.usecase.user.UnfollowCreatorUseCase
import com.snowkey.viday.usecase.video.GetVideosUseCase
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaylistViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val getPlaylistsUseCase: GetPlaylistsUseCase,
    private val getAvailablePlaylistsUseCase: GetAvailablePlaylistsUseCase,
    private val createPlaylistUseCase: CreatePlaylistUseCase,
    private val deletePlaylistUseCase: DeletePlaylistUseCase,
    private val addContentToPlaylistUseCase: AddContentToPlaylistUseCase,
    private val removeContentFromPlaylistUseCase: RemoveContentFromPlaylistUseCase,
    private val followCreatorUseCase: FollowCreatorUseCase,
    private val unfollowCreatorUseCase: UnfollowCreatorUseCase,
    private val getUserByIdUseCase: GetUserByIdUseCase,
    private val getVideosUseCase: GetVideosUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlaylistUiState>(PlaylistUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _playlists = MutableStateFlow<List<PlaylistView>>(emptyList())
    val playlists = _playlists.asStateFlow()

    private val _userId = MutableStateFlow<Long?>(null)
    val userId = _userId.asStateFlow()

    private val _userNamesCache = MutableStateFlow<Map<Long, String>>(emptyMap())

    private val _events = MutableSharedFlow<PlaylistUiEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = _events.asSharedFlow()

    init {
        loadPlaylists()
    }

    fun loadPlaylists() {
        viewModelScope.launch {
            _uiState.value = PlaylistUiState.Loading
            _userId.value = savedStateHandle.get<Long>("userId")?.takeIf { it != -1L }

            val result = getAvailablePlaylistsUseCase()
            if (result.playlists != null) {
                _playlists.value = mapPlaylistsWithPreviewAndOwners(result.playlists!!)
                _uiState.value = PlaylistUiState.Done
            } else {
                val messageId = result.error.toErrorMessageId()
                _uiState.value =
                    PlaylistUiState.Error(messageId) { loadPlaylists() }
            }
        }
    }

    private fun reloadPlaylistsQuiet() {
        viewModelScope.launch {
            val result = getAvailablePlaylistsUseCase()
            if (result.playlists != null) {
                _playlists.value = mapPlaylistsWithPreviewAndOwners(result.playlists!!)
                _uiState.value = PlaylistUiState.Done
            } else {
                _events.emit(
                    PlaylistUiEvent.Message(result.error.toErrorMessageId()),
                )
            }
        }
    }

    fun createPlaylist(name: String, accessType: String) {
        viewModelScope.launch {
            val result = createPlaylistUseCase(name, accessType)
            if (result.error == PlaylistError.OK) reloadPlaylistsQuiet()
            else emitMessage(result.error.toErrorMessageId())
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            val result = deletePlaylistUseCase(playlistId)
            if (result.error == PlaylistError.OK) reloadPlaylistsQuiet()
            else emitMessage(result.error.toErrorMessageId())
        }
    }

    fun addContentToPlaylist(playlistId: Long, contentId: Long, position: Int) {
        viewModelScope.launch {
            val result = addContentToPlaylistUseCase(playlistId, contentId, position)
            if (result.error == PlaylistError.OK) reloadPlaylistsQuiet()
            else emitMessage(result.error.toErrorMessageId())
        }
    }

    fun removeContentFromPlaylist(playlistId: Long, contentId: Long) {
        viewModelScope.launch {
            val result = removeContentFromPlaylistUseCase(playlistId, contentId)
            if (result.error == PlaylistError.OK) reloadPlaylistsQuiet()
            else emitMessage(result.error.toErrorMessageId())
        }
    }

    fun followCreator(creatorUserId: Long) {
        viewModelScope.launch {
            val result = followCreatorUseCase(creatorUserId)
            if (result.error != UserError.OK) {
                emitMessage(result.error.toErrorMessageId())
            }
        }
    }

    fun unfollowCreator(creatorUserId: Long) {
        viewModelScope.launch {
            val result = unfollowCreatorUseCase(creatorUserId)
            if (result.error != UserError.OK) {
                emitMessage(result.error.toErrorMessageId())
            }
        }
    }

    private fun emitMessage(@StringRes id: Int) {
        viewModelScope.launch {
            _events.emit(PlaylistUiEvent.Message(id))
        }
    }

    private suspend fun mapPlaylistsWithPreviewAndOwners(playlists: List<Playlist>): List<PlaylistView> {
        val ownerIds = playlists.map { it.ownerId }.distinct()
        loadOwnerNames(ownerIds)

        return playlists.map { playlist: Playlist ->
            val firstVideoPreview = getVideosUseCase(userId = null, playlistId = playlist.id).videos
                ?.firstOrNull()
                ?.preview

            playlist.toPlaylistView().copy(
                previewUrl = firstVideoPreview,
                ownerName = _userNamesCache.value[playlist.ownerId] ?: "User ${playlist.ownerId}"
            )
        }
    }

    private suspend fun loadOwnerNames(ownerIds: List<Long>) {
        ownerIds.forEach { ownerId ->
            if (!_userNamesCache.value.containsKey(ownerId)) {
                val result = getUserByIdUseCase(ownerId)
                if (result.error == UserError.OK && result.users != null) {
                    _userNamesCache.value = _userNamesCache.value + (ownerId to result.users!![0].username)
                } else {
                    _userNamesCache.value = _userNamesCache.value + (ownerId to "User $ownerId")
                }
            }
        }
    }

    private suspend fun mapPlaylistsWithPreview(playlists: List<Playlist>): List<PlaylistView> {
        return mapPlaylistsWithPreviewAndOwners(playlists)
    }
}
