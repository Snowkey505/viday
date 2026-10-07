package com.vidayapi.service

import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.Role
import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.port.PasswordEncoder
import com.vidayapi.port.UserRepository
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val jwtService: JwtService
) {

    fun login(username: String, password: String, passwordEncoder: PasswordEncoder): Result<String> {
        return userRepository.findByUsername(username)
            .flatMap { user ->
                if (user == null) {
                    Error.Unauthorized.left()
                } else if (!passwordEncoder.matches(password, user.passwordHash)) {
                    Error.Unauthorized.left()
                } else {
                    val userId = user.id
                    if (userId == null) {
                        Error.StorageFailure.left()
                    } else {
                        val token = jwtService.generateToken(userId, user.username, user.role)
                        token.right()
                    }
                }
            }
    }

    fun validateToken(token: String): Result<Int> {
        return jwtService.validateAndGetUserId(token)
    }

    fun getCurrentUserRole(token: String): Result<Role> {
        return jwtService.validateAndGetUserId(token)
            .flatMap { userId ->
                userRepository.findById(userId)
                    .flatMap { user ->
                        user?.role?.right() ?: Error.NotFound.left()
                    }
            }
    }

    fun canCreatePlaylist(token: String): Result<Unit> {
        val role = jwtService.getRoleFromToken(token)
        return when (role) {
            Role.USER, Role.CREATOR, Role.ADMIN -> Unit.right()
            else -> Error.Unauthorized.left()
        }
    }

    fun canUploadVideo(token: String): Result<Unit> {
        val role = jwtService.getRoleFromToken(token)
        return when (role) {
            Role.CREATOR, Role.ADMIN -> Unit.right()
            else -> Error.CreatorRequired.left()
        }
    }

    fun getRoleForUserId(userId: Int): Result<Role> =
        userRepository.findById(userId).flatMap { user ->
            user?.role?.right() ?: Error.NotFound.left()
        }

    fun refreshToken(token: String): Result<String> =
        jwtService.validateAndGetUserId(token).flatMap { userId ->
            userRepository.findById(userId).flatMap { user ->
                if (user != null) {
                    val newToken = jwtService.generateToken(
                        userId = user.id!!,
                        username = user.username,
                        role = user.role
                    )
                    newToken.right()
                } else {
                    Error.NotFound.left()
                }
            }
        }
}
