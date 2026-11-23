package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.port.UserRepository
import com.vidayapi.service.RoleAuthorization

class AdminDeleteUserUseCase(
    private val userRepository: UserRepository,
) {
    fun execute(targetUserId: Int, adminUserId: Int): Result<Unit> =
        userRepository.findById(adminUserId).flatMap { admin ->
            if (admin == null) return Error.NotFound.left()
            RoleAuthorization.requireAdmin(admin.role).flatMap {
                if (targetUserId == adminUserId) {
                    Error.ValidationFailed("Cannot delete your own account").left()
                } else {
                    userRepository.deleteById(targetUserId)
                }
            }
        }
}
