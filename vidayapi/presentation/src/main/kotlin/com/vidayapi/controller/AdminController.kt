package com.vidayapi.controller

import com.vidayapi.dto.responses.UserResponse
import com.vidayapi.model.Error
import com.vidayapi.service.AuthService
import com.vidayapi.usecase.AdminDeleteContentUseCase
import com.vidayapi.usecase.AdminDeletePlaylistUseCase
import com.vidayapi.usecase.AdminDeleteUserUseCase
import com.vidayapi.usecase.AssignAdminRoleUseCase
import com.vidayapi.usecase.AssignAnalystRoleUseCase
import com.vidayapi.controller.support.authenticatedUser
import com.vidayapi.controller.support.forbiddenResponse
import com.vidayapi.controller.support.unauthorizedResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val authService: AuthService,
    private val adminDeleteUserUseCase: AdminDeleteUserUseCase,
    private val adminDeleteContentUseCase: AdminDeleteContentUseCase,
    private val adminDeletePlaylistUseCase: AdminDeletePlaylistUseCase,
    private val assignAdminRoleUseCase: AssignAdminRoleUseCase,
    private val assignAnalystRoleUseCase: AssignAnalystRoleUseCase,
) {
    @DeleteMapping("/users/{userId}")
    fun deleteUser(
        @PathVariable userId: Int,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val adminId = resolveAdminId(userDetails) ?: return unauthorizedResponse()
        return adminDeleteUserUseCase.execute(userId, adminId).fold(
            onSuccess = { ResponseEntity.noContent().build() },
            onFailure = { error -> adminError(error as Error) }
        )
    }

    @DeleteMapping("/contents/{contentId}")
    fun deleteContent(
        @PathVariable contentId: Int,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val adminId = resolveAdminId(userDetails) ?: return unauthorizedResponse()
        return adminDeleteContentUseCase.execute(contentId, adminId).fold(
            onSuccess = { ResponseEntity.noContent().build() },
            onFailure = { error -> adminError(error as Error) }
        )
    }

    @DeleteMapping("/playlists/{playlistId}")
    fun deletePlaylist(
        @PathVariable playlistId: Int,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val adminId = resolveAdminId(userDetails) ?: return unauthorizedResponse()
        return adminDeletePlaylistUseCase.execute(playlistId, adminId).fold(
            onSuccess = { ResponseEntity.noContent().build() },
            onFailure = { error -> adminError(error as Error) }
        )
    }

    @PostMapping("/users/{username}/promote-admin")
    fun promoteToAdmin(
        @PathVariable username: String,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val adminId = resolveAdminId(userDetails) ?: return unauthorizedResponse()
        return assignAdminRoleUseCase.execute(username, adminId).fold(
            onSuccess = { user ->
                ResponseEntity.ok(
                    UserResponse(
                        id = user.id?.toLong() ?: 0L,
                        username = user.username,
                        role = user.role.name,
                    ),
                )
            },
            onFailure = { error -> adminError(error as Error) }
        )
    }

    @PostMapping("/users/{username}/promote-analyst")
    fun promoteToAnalyst(
        @PathVariable username: String,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val adminId = resolveAdminId(userDetails) ?: return unauthorizedResponse()
        return assignAnalystRoleUseCase.execute(username, adminId).fold(
            onSuccess = { user ->
                ResponseEntity.ok(
                    UserResponse(
                        id = user.id?.toLong() ?: 0L,
                        username = user.username,
                        role = user.role.name,
                    ),
                )
            },
            onFailure = { error -> adminError(error as Error) }
        )
    }

    private fun resolveAdminId(userDetails: UserDetails?): Int? =
        authService.authenticatedUser(userDetails).getOrNull()?.first

    private fun adminError(error: Error): ResponseEntity<Any> =
        when (error) {
            is Error.Forbidden -> forbiddenResponse()
            is Error.NotFound -> ResponseEntity.notFound().build()
            is Error.ValidationFailed ->
                ResponseEntity.badRequest().body(mapOf("error" to error.details))
            else -> ResponseEntity.status(500).body(mapOf("error" to "Admin operation failed"))
        }
}
