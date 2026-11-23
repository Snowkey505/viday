package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.port.UserRepository

class FollowUserUseCase(
    private val userRepository: UserRepository,
) {
    fun execute(followerId: Int, creatorId: Int): Result<Unit> {
        if (followerId == creatorId) {
            return Error.CannotFollowSelf.left()
        }

        return userRepository.findById(creatorId).flatMap { creator ->
            if (creator == null) {
                Error.NotFound.left()
            } else {
                userRepository.follow(followerId, creatorId)
            }
        }
    }
}
