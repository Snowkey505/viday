package com.vidayapi.controller

import com.vidayapi.dto.responses.LoginResponse
import com.vidayapi.dto.responses.UserResponse
import com.vidayapi.model.Error
import com.vidayapi.service.AuthService
import com.vidayapi.usecase.ActivateChannelUseCase
import com.vidayapi.controller.support.authenticatedUser
import com.vidayapi.controller.support.unauthorizedResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users/me")
class ChannelController(
    private val activateChannelUseCase: ActivateChannelUseCase,
    private val authService: AuthService,
) {
    @PostMapping("/channel/activate")
    fun activateChannel(@AuthenticationPrincipal userDetails: UserDetails?): ResponseEntity<Any> {
        val (userId, _) = authService.authenticatedUser(userDetails).getOrNull()
            ?: return unauthorizedResponse()

        return activateChannelUseCase.execute(userId).fold(
            onSuccess = { user ->
                ResponseEntity.ok(
                    mapOf(
                        "message" to "Channel activated. You are now a CREATOR.",
                        "user" to UserResponse(
                            id = user.id?.toLong() ?: 0L,
                            username = user.username,
                            role = user.role.name,
                        ),
                        "hint" to "Call POST /api/users/me/token/refresh to update JWT",
                    ),
                )
            },
            onFailure = { error ->
                when (error) {
                    is Error.AlreadyCreator ->
                        ResponseEntity.status(409).body(mapOf("error" to "Channel already activated"))
                    is Error.InvalidRoleTransition ->
                        ResponseEntity.status(403).body(mapOf("error" to "Role cannot be upgraded to CREATOR"))
                    is Error.ValidationFailed ->
                        ResponseEntity.badRequest().body(mapOf("error" to error.details))
                    else ->
                        ResponseEntity.status(500).body(mapOf("error" to "Activation failed"))
                }
            }
        )
    }

    @PostMapping("/token/refresh")
    fun refreshToken(
        @AuthenticationPrincipal userDetails: UserDetails?,
        @org.springframework.web.bind.annotation.RequestHeader("Authorization") authHeader: String?,
    ): ResponseEntity<Any> {
        if (userDetails == null || authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorizedResponse()
        }
        val token = authHeader.substring(7)
        return authService.refreshToken(token).fold(
            onSuccess = { newToken -> ResponseEntity.ok(LoginResponse(token = newToken)) },
            onFailure = {
                ResponseEntity.status(401).body(mapOf("error" to "Failed to refresh token"))
            }
        )
    }
}
