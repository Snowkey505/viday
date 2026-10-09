package com.snowkey.viday.presentation.loginScreen

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snowkey.viday.R
import com.snowkey.viday.data.PreferencesLocalDataSource
import com.snowkey.viday.mapper.toErrorMessageId
import com.snowkey.viday.model.AuthData
import com.snowkey.viday.model.AuthError
import com.snowkey.viday.usecase.auth.LoginUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(
    private val loginUseCase: LoginUseCase,
    private val prefsDataSource: PreferencesLocalDataSource
) : ViewModel() {
    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _usernameField = MutableStateFlow("")
    val usernameField: StateFlow<String> = _usernameField

    private val _passwordField = MutableStateFlow("")
    val passwordField: StateFlow<String> = _passwordField

    private val _showPasswordDialog = MutableStateFlow(false)
    val showPasswordDialog = _showPasswordDialog.asStateFlow()

    fun updateUsername(username: String) {
        _usernameField.value = username
    }

    fun updatePassword(password: String) {
        _passwordField.value = password
    }

    fun togglePasswordDialog() {
        _showPasswordDialog.value = !_showPasswordDialog.value
    }

    fun login() {
        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading
            try {
                val response = loginUseCase(
                    username = _usernameField.value,
                    password = _passwordField.value
                )
                if (response.error == AuthError.OK) {
                    val authData = AuthData(
                        _usernameField.value,
                        _passwordField.value
                    )
                    Log.d("AUTH", "setting ${authData}")
                    prefsDataSource.setAuthData(authData)
                    _uiState.value = LoginUiState.Success(response.userId!!)
                } else {
                    _uiState.value = LoginUiState.Error(
                        messageResId = response.error.toErrorMessageId(),
                        onRepeat = { login() }
                    )
                }
            } catch (e: Exception) {
                _uiState.value = LoginUiState.Error(
                    messageResId = R.string.login_error,
                    onRepeat = { login() }
                )
            }
        }
    }

    suspend fun loginBackground(login: String, password: String): Long? {
        Log.d("AUTH", "loging background")
        return try {
            val response = loginUseCase(
                username = login,
                password = password
            )
            Log.d("AUTH", "loging background = ${response.error}")
            if (response.error == AuthError.OK) {
                response.userId!!
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
