package com.snowkey.viday.presentation.videosScreen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snowkey.viday.R
import com.snowkey.viday.mapper.toErrorMessageId
import com.snowkey.viday.mapper.toPlaylistView
import com.snowkey.viday.mapper.toUi
import com.snowkey.viday.model.PlaylistError
import com.snowkey.viday.model.PlaylistView
import com.snowkey.viday.model.VideoError
import com.snowkey.viday.model.VideoView
import com.snowkey.viday.usecase.playlist.AddContentToPlaylistUseCase
import com.snowkey.viday.usecase.playlist.GetPlaylistByIdUseCase
import com.snowkey.viday.usecase.playlist.GetPlaylistsUseCase
import com.snowkey.viday.usecase.playlist.RemoveContentFromPlaylistUseCase
import com.snowkey.viday.usecase.video.GetVideosUseCase
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VideosViewModel(
    private val getVideosUseCase: GetVideosUseCase,
    private val getPlaylistsUseCase: GetPlaylistsUseCase,
    private val addContentToPlaylistUseCase: AddContentToPlaylistUseCase,
    private val getPlaylistByIdUseCase: GetPlaylistByIdUseCase,
    private val removeContentFromPlaylistUseCase: RemoveContentFromPlaylistUseCase,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow<VideosUiState>(VideosUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _videos = MutableStateFlow<List<VideoView>>(emptyList())
    val videos = _videos.asStateFlow()

    private val _playlists = MutableStateFlow<List<PlaylistView>>(emptyList())
    val playlists = _playlists.asStateFlow()

    private val _userId = MutableStateFlow<Long?>(null)
    val userId = _userId.asStateFlow()

    private val _playlistId = MutableStateFlow<Long?>(null)
    val playlistId = _playlistId.asStateFlow()

    private val _playlistInfo = MutableStateFlow<PlaylistView?>(null)
    val playlistInfo = _playlistInfo.asStateFlow()

    private val _events = MutableSharedFlow<Int>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = _events.asSharedFlow()

    fun load() {
        _userId.value = savedStateHandle.get<Long>("userId")?.takeIf { it != -1L }
        _playlistId.value = savedStateHandle.get<Long>("playlistId")?.takeIf { it != -1L }

        if (_playlistId.value != null) {
            loadPlaylistInfo(_playlistId.value!!)
        }

        loadVideos(_userId.value, _playlistId.value)
        loadPlaylists()
    }

    fun clear() {
        _videos.value = emptyList()
        _playlistInfo.value = null
        _playlists.value = emptyList()
    }

    private fun loadPlaylistInfo(playlistId: Long) {
        viewModelScope.launch {
            val result = getPlaylistByIdUseCase(playlistId)
            if (result.playlists != null && result.playlists!!.isNotEmpty()) {
                _playlistInfo.value = result.playlists!![0].toPlaylistView()
            } else {
                _events.emit(result.error.toErrorMessageId())
            }
        }
    }

    fun loadVideos(userId: Long?, playlistId: Long?) {
        if (playlistId != null) {
            getVideos(userId = null, playlistId = playlistId)
        } else if (userId != null) {
            getMainScreenVideos(userId)
        } else {
            getVideos(userId = null, playlistId = null)
        }
    }

    fun getVideos(userId: Long?, playlistId: Long?) {
        viewModelScope.launch {
            val videosResult = getVideosUseCase(userId, playlistId)
            if (videosResult.videos != null) {
                _videos.value = videosResult.videos!!.map { video -> video.toUi() }
                _uiState.value = VideosUiState.Done
            } else {
                val errorMessageId =
                    when (videosResult.error) {
                        VideoError.SERVER_ERROR -> R.string.server_error
                        VideoError.ACCESS_ERROR -> R.string.access_error
                        VideoError.NETWORK_ERROR -> R.string.network_error
                        else -> R.string.unknown_error
                    }
                _uiState.value = VideosUiState.Error(errorMessageId)
            }
        }
    }

    private fun getMainScreenVideos(userId: Long) {
        viewModelScope.launch {
            val userVideosResult = getVideosUseCase(userId = userId, playlistId = null)
            if (userVideosResult.videos == null) {
                _uiState.value = VideosUiState.Error(userVideosResult.error.toErrorMessageId())
                return@launch
            }

            val publicVideosResult = getVideosUseCase(userId = null, playlistId = null)
            if (publicVideosResult.videos == null) {
                _uiState.value =
                    VideosUiState.Error(publicVideosResult.error.toErrorMessageId())
                return@launch
            }

            val mergedVideos = (userVideosResult.videos!! + publicVideosResult.videos!!)
                .distinctBy { video -> video.id }
                .map { video -> video.toUi() }

            _videos.value = mergedVideos
            _uiState.value = VideosUiState.Done
        }
    }

    private fun loadPlaylists() {
        viewModelScope.launch {
            val playlistsResult = getPlaylistsUseCase()
            if (playlistsResult.playlists != null) {
                _playlists.value = playlistsResult.playlists!!.map { it.toPlaylistView() }
            } else {
                _events.emit(playlistsResult.error.toErrorMessageId())
            }
        }
    }

    fun addVideoToPlaylist(videoId: Long, playlistId: Long) {
        viewModelScope.launch {
            val result = addContentToPlaylistUseCase(
                playlistId = playlistId,
                contentId = videoId
            )
            val messageId = result.error.toErrorMessageId()
            _events.emit(messageId)
            if (result.error == PlaylistError.OK) {
                loadPlaylists()
            }
        }
    }

    fun removeVideoFromPlaylist(videoId: Long) {
        if (_playlistId.value != null) {
            viewModelScope.launch {
                val result = removeContentFromPlaylistUseCase(
                    playlistId = _playlistId.value!!,
                    contentId = videoId
                )
                val messageId = result.error.toErrorMessageId()
                _events.emit(messageId)
                if (result.error == PlaylistError.OK) {
                    load()
                }
            }
        }
    }
}
