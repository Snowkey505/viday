package com.vidayapi.controller

import com.vidayapi.dto.requests.LoginRequest
import com.vidayapi.dto.requests.SignUpRequest
import com.vidayapi.dto.responses.LoginResponse
import com.vidayapi.dto.responses.UserResponse
import com.vidayapi.model.Error
import com.vidayapi.port.PasswordEncoder
import com.vidayapi.service.AuthService
import com.vidayapi.usecase.RegisterUserUseCase
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val registerUserUseCase: RegisterUserUseCase,
    private val authService: AuthService,
    private val passwordEncoder: PasswordEncoder
) {
    private val logger = LoggerFactory.getLogger(AuthController::class.java)


    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<Any> =
        authService.login(request.username, request.password, passwordEncoder).fold(
            onSuccess = { token -> ResponseEntity.ok(LoginResponse(token = token)) },
            onFailure = { error ->
                when (error) {
                    is Error.Unauthorized ->
                        ResponseEntity.status(401).body(mapOf("error" to "Invalid credentials"))
                    else -> ResponseEntity.status(500).body(mapOf("error" to "Login failed"))
                }
            }
        )

    @PostMapping("/register")
    fun register(@RequestBody request: SignUpRequest): ResponseEntity<Any> =
        registerUserUseCase.execute(request.username, request.password).fold(
            onSuccess = { user ->
                ResponseEntity.status(201).body(
                    UserResponse(
                        id = user.id?.toLong() ?: 0L,
                        username = user.username,
                        role = user.role.name
                    )
                )
            },
            onFailure = { error ->
                when (error) {
                    is Error.AlreadyExists ->
                        ResponseEntity.status(409).body(mapOf("error" to "User already exists"))
                    is Error.ValidationFailed -> ResponseEntity.status(400).body(
                        mapOf(
                            "error" to "Invalid input",
                            "details" to error.details
                        )
                    )
                    else -> {
                        logger.error("Registration failed for username='{}' with error={}", request.username, error)
                        ResponseEntity.status(500).body(
                            mapOf(
                                "error" to "Registration failed",
                                "details" to error.toString()
                            )
                        )
                    }
                }
            }
        )
}
