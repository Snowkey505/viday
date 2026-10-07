package com.snowkey.viday.usecase.auth

import com.snowkey.viday.repository.AuthRepository

class LogoutUseCase(
    private val authRepository: AuthRepository
) {
    operator fun invoke() {
        authRepository.logout()
    }
}
