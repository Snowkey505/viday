package com.vidayapi.usecase

import com.vidayapi.model.left
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.port.UserRepository

class UnfollowUserUseCase(
    private val userRepository: UserRepository,
) {
    fun execute(followerId: Int, creatorId: Int): Result<Unit> {
        if (followerId == creatorId) {
            return Error.CannotFollowSelf.left()
        }
        return userRepository.unfollow(followerId, creatorId)
    }
}
