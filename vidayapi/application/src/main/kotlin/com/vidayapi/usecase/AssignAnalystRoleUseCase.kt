package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.Role
import com.vidayapi.model.User
import com.vidayapi.port.UserRepository

class AssignAnalystRoleUseCase(
    private val userRepository: UserRepository,
) {
    fun execute(username: String, requestingUserId: Int): Result<User> =
        userRepository.findById(requestingUserId).flatMap { requester ->
            if (requester?.role != Role.ADMIN) {
                Error.Forbidden.left()
            } else {
                userRepository.findByUsername(username).flatMap { user ->
                    if (user == null) {
                        Error.NotFound.left()
                    } else {
                        userRepository.update(user.copy(role = Role.ANALYST))
                    }
                }
            }
        }
}
