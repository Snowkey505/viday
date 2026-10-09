package com.snowkey.viday.usecase.auth

import com.snowkey.viday.repository.AuthRepository
import com.snowkey.viday.model.AuthError
import com.snowkey.viday.model.AuthResult

class LoginUseCase(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(username: String, password: String): AuthResult {
        if (username.isBlank() || password.isBlank()) {
            return AuthResult(error = AuthError.INVALID_CREDENTIALS)
        }
        return authRepository.login(username, password)
    }
}
