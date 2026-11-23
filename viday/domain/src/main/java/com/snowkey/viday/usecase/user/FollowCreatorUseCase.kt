package com.snowkey.viday.usecase.user

import com.snowkey.viday.model.UserError
import com.snowkey.viday.model.UserResult
import com.snowkey.viday.repository.UserRepository

class FollowCreatorUseCase(
    private val userRepository: UserRepository,
) {
    suspend operator fun invoke(creatorUserId: Long): UserResult {
        if (creatorUserId <= 0) {
            return UserResult(error = UserError.UNKNOWN_ERROR)
        }
        return userRepository.followCreator(creatorUserId)
    }
}
