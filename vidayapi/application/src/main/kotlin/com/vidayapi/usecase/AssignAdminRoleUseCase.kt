package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.Result
import com.vidayapi.model.Error
import com.vidayapi.model.Role
import com.vidayapi.model.User
import com.vidayapi.port.UserRepository

class AssignAdminRoleUseCase(
    private val userRepository: UserRepository
) {
    fun execute(username: String, requestingUserId: Int): Result<User> {
        return userRepository.findById(requestingUserId)
            .flatMap { requester ->
                if (requester?.role != Role.ADMIN) {
                    return Error.Forbidden.left()
                }
                userRepository.findByUsername(username)
                    .flatMap { user ->
                        if (user == null) Error.NotFound.left()
                        else {
                            val updatedUser = user.copy(role = Role.ADMIN)
                            userRepository.update(updatedUser)
                        }
                    }
            }
    }
}
