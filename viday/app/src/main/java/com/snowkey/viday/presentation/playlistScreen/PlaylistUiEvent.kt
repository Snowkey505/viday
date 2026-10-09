package com.snowkey.viday.presentation.playlistScreen

import androidx.annotation.StringRes

sealed interface PlaylistUiEvent {
    data class Message(
        @StringRes val messageResId: Int,
    ) : PlaylistUiEvent
}
