package com.snowkey.viday.presentation.videoScreen

import androidx.annotation.StringRes

sealed class VideoUiState {
    data object Loading : VideoUiState()

    data object Done : VideoUiState()
    
    data class Error(
        @StringRes val messageResId: Int,
        val onRepeat: () -> Unit = {}
    ) : VideoUiState()
}
