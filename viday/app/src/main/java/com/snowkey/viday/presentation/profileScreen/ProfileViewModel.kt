package com.snowkey.viday.presentation.profileScreen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snowkey.viday.data.PreferencesLocalDataSource
import com.snowkey.viday.usecase.auth.LogoutUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val logoutUseCase: LogoutUseCase,
    private val prefsDataSource: PreferencesLocalDataSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _showPasswordDialog = MutableStateFlow(false)
    val showPasswordDialog = _showPasswordDialog.asStateFlow()

    private val _username = MutableStateFlow("")
    val username = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password = _password.asStateFlow()

    init {
        viewModelScope.launch {
            val authData = prefsDataSource.getAuthData()
            authData?.let {
                _username.value = authData.login
                _password.value = authData.password
            }
        }
    }

    fun togglePasswordDialog() {
        _showPasswordDialog.value = !_showPasswordDialog.value
    }

    fun logout() {
        viewModelScope.launch {
            logoutUseCase()
            prefsDataSource.clearAuthData()
            _uiState.value = ProfileUiState.Logout
        }
    }
}
