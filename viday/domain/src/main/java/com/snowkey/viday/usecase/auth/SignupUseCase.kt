package com.snowkey.viday.usecase.auth

import com.snowkey.viday.model.AuthError
import com.snowkey.viday.model.AuthResult
import com.snowkey.viday.repository.AuthRepository

class SignupUseCase(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(username: String, password: String): AuthResult {
        if (username.isBlank() || password.isBlank()) {
            return AuthResult(error = AuthError.INVALID_CREDENTIALS)
        }
        if (password.length < 6) {
            return AuthResult(error = AuthError.INVALID_CREDENTIALS)
        }
        return authRepository.signup(username, password)
    }
}
