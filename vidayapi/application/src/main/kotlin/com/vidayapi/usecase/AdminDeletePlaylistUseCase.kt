package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.port.PlaylistRepository
import com.vidayapi.port.UserRepository
import com.vidayapi.service.RoleAuthorization

class AdminDeletePlaylistUseCase(
    private val userRepository: UserRepository,
    private val playlistRepository: PlaylistRepository,
) {
    fun execute(playlistId: Int, adminUserId: Int): Result<Unit> =
        userRepository.findById(adminUserId).flatMap { admin ->
            if (admin == null) return Error.NotFound.left()
            RoleAuthorization.requireAdmin(admin.role).flatMap {
                playlistRepository.deleteByIdAsAdmin(playlistId)
            }
        }
}
