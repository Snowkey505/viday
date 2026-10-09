package com.snowkey.viday.usecase.user

import com.snowkey.viday.model.UserResult
import com.snowkey.viday.repository.UserRepository

class GetUserByIdUseCase(
    private val repository: UserRepository
) {
    suspend operator fun invoke(userId: Long): UserResult {
        return repository.getUserById(userId)
    }
}
