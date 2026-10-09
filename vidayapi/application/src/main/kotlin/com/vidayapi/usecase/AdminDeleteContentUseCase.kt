package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.Role
import com.vidayapi.port.UserRepository
import com.vidayapi.service.ContentDeletionService
import com.vidayapi.service.RoleAuthorization

class AdminDeleteContentUseCase(
    private val userRepository: UserRepository,
    private val contentDeletionService: ContentDeletionService,
) {
    fun execute(contentId: Int, adminUserId: Int): Result<Unit> =
        userRepository.findById(adminUserId).flatMap { admin ->
            if (admin == null) return Error.NotFound.left()
            RoleAuthorization.requireAdmin(admin.role).flatMap {
                contentDeletionService.deleteContentAsAdmin(contentId)
            }
        }
}
