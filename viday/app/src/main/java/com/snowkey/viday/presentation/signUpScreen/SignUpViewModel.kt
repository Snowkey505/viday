package com.snowkey.viday.presentation.signUpScreen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snowkey.viday.usecase.auth.SignupUseCase
import com.snowkey.viday.model.AuthError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.snowkey.viday.R
import com.snowkey.viday.mapper.toErrorMessageId


class SignUpViewModel(
    private val signupUseCase: SignupUseCase
) : ViewModel() {
    private val _uiState = MutableStateFlow<SignUpUiState>(SignUpUiState.Idle)
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

    fun signUp() {
        viewModelScope.launch {
            _uiState.value = SignUpUiState.Loading
            try {
                val response = signupUseCase(
                    username = _usernameField.value,
                    password = _passwordField.value
                )
                if (response.error == AuthError.OK) {
                    _uiState.value = SignUpUiState.Success
                } else {
                    _uiState.value = SignUpUiState.Error(
                        messageResId = response.error.toErrorMessageId(),
                        onRepeat = { signUp() }
                    )
                }
            } catch (e: Exception) {
                _uiState.value = SignUpUiState.Error(
                    messageResId = R.string.sign_up_error,
                    onRepeat = { signUp() }
                )
            }
        }
    }
}
