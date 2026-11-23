package com.snowkey.viday.presentation.playlistScreen

import androidx.annotation.StringRes

sealed class PlaylistUiState {
    data object Loading : PlaylistUiState()

    data object Done : PlaylistUiState()

    data class Error(
        @StringRes val messageResId: Int,
        val onRepeat: () -> Unit = {},
    ) : PlaylistUiState()
}
