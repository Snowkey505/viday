package com.snowkey.viday.presentation.videoScreen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snowkey.viday.R
import com.snowkey.viday.mapper.toErrorMessageId
import com.snowkey.viday.mapper.toPlaylistView
import com.snowkey.viday.mapper.toUi
import com.snowkey.viday.model.PlaylistView
import com.snowkey.viday.model.UserError
import com.snowkey.viday.model.VideoView
import com.snowkey.viday.usecase.playlist.AddContentToPlaylistUseCase
import com.snowkey.viday.usecase.playlist.GetPlaylistsUseCase
import com.snowkey.viday.usecase.playlist.RemoveContentFromPlaylistUseCase
import com.snowkey.viday.usecase.user.GetUserByIdUseCase
import com.snowkey.viday.usecase.video.GetVideoByIdUseCase
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VideoViewModel(
    private val getVideoByIdUseCase: GetVideoByIdUseCase,
    private val getPlaylistsUseCase: GetPlaylistsUseCase,
    private val addContentToPlaylistUseCase: AddContentToPlaylistUseCase,
    private val removeContentFromPlaylistUseCase: RemoveContentFromPlaylistUseCase,
    private val getUserByIdUseCase: GetUserByIdUseCase,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow<VideoUiState>(VideoUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _video = MutableStateFlow<VideoView?>(null)
    val video = _video.asStateFlow()

    private val _playlists = MutableStateFlow<List<PlaylistView>>(emptyList())
    val playlists = _playlists.asStateFlow()

    private val _userId = MutableStateFlow<Long?>(null)
    val userId = _userId.asStateFlow()

    private val _owner = MutableStateFlow("")
    val owner = _owner.asStateFlow()

    private val _events = MutableSharedFlow<Int>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = _events.asSharedFlow()

    private var currentPlaylistId: Long? = null

    init {
        load()
    }

    fun load() {
        _userId.value = savedStateHandle.get<Long>("userId")?.takeIf { it != -1L }
        val videoId = savedStateHandle.get<Long>("videoId")?.takeIf { it != -1L }
        currentPlaylistId = savedStateHandle.get<Long>("playlistId")?.takeIf { it != -1L }

        if (videoId != null) {
            loadVideo(videoId)
            loadPlaylists()
        } else {
            _uiState.value = VideoUiState.Error(R.string.unknown_error)
        }
    }

    private fun loadVideo(videoId: Long) {
        viewModelScope.launch {
            _uiState.value = VideoUiState.Loading

            val result = getVideoByIdUseCase(videoId, _userId.value)

            if (result.videos != null && result.videos!!.isNotEmpty()) {
                _video.value = result.videos!!.first().toUi()
                loadOwnerInfo(_video.value!!.ownerId)
                _uiState.value = VideoUiState.Done
            } else {
                val messageId = result.error.toErrorMessageId()
                _uiState.value = VideoUiState.Error(messageId) { load() }
            }
        }
    }

    private suspend fun loadOwnerInfo(ownerId: Long) {
        val result = getUserByIdUseCase(ownerId)
        if (result.error == UserError.OK) {
            _owner.value = result.users!![0].username
        }
    }

    private fun loadPlaylists() {
        viewModelScope.launch {
            val playlistsResult = getPlaylistsUseCase()
            if (playlistsResult.playlists != null) {
                _playlists.value = playlistsResult.playlists!!.map { it.toPlaylistView() }
            }
        }
    }

    fun addToPlaylist(playlistId: Long, videoId: Long) {
        viewModelScope.launch {
            val result = addContentToPlaylistUseCase(
                playlistId = playlistId,
                contentId = videoId
            )
            val messageId = result.error.toErrorMessageId()
            _events.emit(messageId)
        }
    }

    fun clear() {
        _video.value = null
        _playlists.value = emptyList()
    }
}
