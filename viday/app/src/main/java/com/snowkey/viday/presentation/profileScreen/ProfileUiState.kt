package com.snowkey.viday.presentation.profileScreen

sealed class ProfileUiState {
    object Idle : ProfileUiState()
    object Logout : ProfileUiState()
}
