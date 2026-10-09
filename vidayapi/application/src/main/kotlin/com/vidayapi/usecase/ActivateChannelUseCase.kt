package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.Role
import com.vidayapi.model.User
import com.vidayapi.port.UserRepository

class ActivateChannelUseCase(
    private val userRepository: UserRepository
) {
    fun execute(userId: Int): Result<User> {
        return userRepository.findById(userId)
            .flatMap { user ->
                if (user == null) Error.NotFound.left()
                else when (user.role) {
                    Role.CREATOR -> Error.AlreadyCreator.left()
                    Role.ADMIN -> Error.InvalidRoleTransition.left()
                    Role.GUEST -> Error.ValidationFailed("Please register as USER first").left()
                    Role.USER -> {
                        val updatedUser = user.copy(role = Role.CREATOR)
                        userRepository.update(updatedUser)
                    }
                    Role.ANALYST -> Error.InvalidRoleTransition.left()
                }
            }
    }
}