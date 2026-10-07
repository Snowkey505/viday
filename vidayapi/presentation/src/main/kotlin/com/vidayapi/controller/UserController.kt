package com.vidayapi.controller

import com.vidayapi.model.Error
import com.vidayapi.port.UserRepository
import com.vidayapi.usecase.FollowUserUseCase
import com.vidayapi.usecase.UnfollowUserUseCase
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userRepository: UserRepository,
    private val followUserUseCase: FollowUserUseCase,
    private val unfollowUserUseCase: UnfollowUserUseCase,
) {
    @GetMapping("/{userId}")
    fun getUserById(
        @PathVariable userId: Int,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        if (userDetails == null) {
            return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))
        }

        return userRepository.findById(userId).fold(
            onSuccess = { user ->
                if (user == null) {
                    ResponseEntity.status(404).body(mapOf("error" to "User not found"))
                } else {
                    ResponseEntity.ok(
                        mapOf(
                            "id" to (user.id ?: 0),
                            "username" to (user.username ?: ""),
                            "role" to user.role.name,
                            "createdAt" to (user.createdAt?.toString() ?: ""),
                        ),
                    )
                }
            },
            onFailure = { error ->
                when (error) {
                    is Error.NotFound -> ResponseEntity.status(404).body(mapOf("error" to "User not found"))
                    else -> ResponseEntity.status(500).body(mapOf("error" to "Failed to fetch user"))
                }
            }
        )
    }

    @PostMapping("/{userId}/follow")
    fun followCreator(
        @PathVariable userId: Int,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val followerId = resolveUserId(userDetails) ?: return unauthorized()

        return followUserUseCase.execute(followerId, userId).fold(
            onSuccess = { ResponseEntity.ok(mapOf("message" to "Subscribed")) },
            onFailure = { error ->
                when (error) {
                    is Error.CannotFollowSelf ->
                        ResponseEntity.badRequest().body(mapOf("error" to "Cannot follow yourself"))
                    is Error.UserAlreadyFollowed ->
                        ResponseEntity.status(409).body(mapOf("error" to "Already following"))
                    is Error.NotFound ->
                        ResponseEntity.status(404).body(mapOf("error" to "User not found"))
                    else -> ResponseEntity.status(500).body(mapOf("error" to "Failed to follow"))
                }
            }
        )
    }

    @DeleteMapping("/{userId}/follow")
    fun unfollowCreator(
        @PathVariable userId: Int,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val followerId = resolveUserId(userDetails) ?: return unauthorized()

        return unfollowUserUseCase.execute(followerId, userId).fold(
            onSuccess = { ResponseEntity.noContent().build() },
            onFailure = { error ->
                when (error) {
                    is Error.CannotFollowSelf ->
                        ResponseEntity.badRequest().body(mapOf("error" to "Cannot unfollow yourself"))
                    is Error.NotFound ->
                        ResponseEntity.status(404).body(mapOf("error" to "Not following this user"))
                    else -> ResponseEntity.status(500).body(mapOf("error" to "Failed to unfollow"))
                }
            }
        )
    }

    private fun resolveUserId(userDetails: UserDetails?): Int? =
        userDetails?.username?.toIntOrNull()

    private fun unauthorized(): ResponseEntity<Any> =
        ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))
}
