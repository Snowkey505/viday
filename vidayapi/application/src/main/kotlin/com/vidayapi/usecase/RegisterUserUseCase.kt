package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.Role
import com.vidayapi.model.User
import com.vidayapi.port.PasswordEncoder
import com.vidayapi.port.UserRepository
import java.time.OffsetDateTime

class RegisterUserUseCase(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {
    fun execute(username: String, plainPassword: String): Result<User> {
        if (username.isBlank() || plainPassword.isBlank()) {
            return Error.ValidationFailed("Username/password blank").left()
        }
        return userRepository.existsByUsername(username)
            .flatMap { exists ->
                if (exists) Error.AlreadyExists.left()
                else {
                    val hashed = passwordEncoder.encode(plainPassword)
                    val newUser = User(
                        username = username,
                        passwordHash = hashed,
                        role = Role.USER,
                        createdAt = OffsetDateTime.now()
                    )
                    userRepository.save(newUser)
                }
            }
    }
}
