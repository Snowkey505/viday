package com.snowkey.viday.presentation.loginScreen

import androidx.annotation.StringRes

sealed class LoginUiState {
    object Idle : LoginUiState()
    object Loading : LoginUiState()
    data class Success(val userId: Long) : LoginUiState()

    data class Error(
        @StringRes val messageResId: Int,
        val onRepeat: () -> Unit = {}
    ) : LoginUiState()
}
