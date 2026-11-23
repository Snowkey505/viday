package com.snowkey.viday.presentation.signUpScreen

import androidx.annotation.StringRes

sealed class SignUpUiState {
    object Idle : SignUpUiState()
    object Loading : SignUpUiState()
    object Success : SignUpUiState()

    data class Error(
        @StringRes val messageResId: Int,
        val onRepeat: () -> Unit = {}
    ) : SignUpUiState()
}
