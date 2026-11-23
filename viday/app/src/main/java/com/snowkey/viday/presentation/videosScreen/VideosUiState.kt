package com.snowkey.viday.presentation.videosScreen

import androidx.annotation.StringRes

sealed class VideosUiState {
    data object Loading : VideosUiState()

    data object Done : VideosUiState()

    data class Error(
        @StringRes val messageResId: Int,
        val onRepeat: () -> Unit = {}
    ) : VideosUiState()
}
