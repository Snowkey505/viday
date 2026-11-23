package com.vidayapi.controller

import com.vidayapi.dto.requests.LoginRequest
import com.vidayapi.dto.requests.PasswordChangeRequest
import com.vidayapi.dto.requests.PasswordRecoveryConfirmRequest
import com.vidayapi.dto.requests.PasswordRecoveryRequest
import com.vidayapi.dto.requests.SignUpRequest
import com.vidayapi.dto.requests.VerifyTwoFactorRequest
import com.vidayapi.dto.responses.AccountLockedResponse
import com.vidayapi.dto.responses.InvalidCredentialsResponse
import com.vidayapi.dto.responses.LoginResponse
import com.vidayapi.dto.responses.MessageResponse
import com.vidayapi.dto.responses.PasswordChangeRequiredResponse
import com.vidayapi.dto.responses.TwoFactorChallengeResponse
import com.vidayapi.dto.responses.UserResponse
import com.vidayapi.model.Error
import com.vidayapi.service.LoginOutcome
import com.vidayapi.service.TwoFactorOutcome
import com.vidayapi.service.AuthenticationService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Аутентификация (ЛР2 + ЛР5).
 *
 * Все эндпоинты анонимные; RLS-фильтр переключает их на ограниченную роль
 * viday_auth (см. RlsContextResolver), поэтому обработка логина не получает
 * прав администратора стенда.
 *
 * Сценарий входа:
 *  1. POST /api/auth/login — пароль;
 *     * 200 + JWT — успех;
 *     * 202 + challengeId — включён второй фактор, нужен код из письма;
 *     * 200 + passwordChangeToken — пароль просрочен, доступен только его смена;
 *     * 401 + attemptsRemaining — неверный пароль;
 *     * 423 + lockedUntil — превышен лимит попыток;
 *  2. POST /api/auth/2fa/verify — код из письма; выдаёт JWT либо
 *     ограниченный токен смены пароля;
 *  3. POST /api/auth/recovery/request|confirm — восстановление после блокировки.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authenticationService: AuthenticationService,
) {
    private val logger = LoggerFactory.getLogger(AuthController::class.java)

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<Any> =
        authenticationService.login(request.username, request.password).fold(
            onSuccess = { outcome ->
                when (outcome) {
                    is LoginOutcome.Authenticated ->
                        ResponseEntity.ok(LoginResponse(token = outcome.token))

                    is LoginOutcome.TwoFactorRequired ->
                        ResponseEntity.accepted().body(
                            TwoFactorChallengeResponse(
                                challengeId = outcome.challengeId,
                                channel = outcome.channel,
                                emailMasked = outcome.emailMasked,
                            ),
                        )

                    is LoginOutcome.PasswordChangeRequired ->
                        ResponseEntity.ok(
                            PasswordChangeRequiredResponse(passwordChangeToken = outcome.passwordChangeToken),
                        )

                    is LoginOutcome.InvalidCredentials ->
                        ResponseEntity.status(401).body(
                            InvalidCredentialsResponse(attemptsRemaining = outcome.attemptsRemaining),
                        )

                    is LoginOutcome.Locked ->
                        ResponseEntity.status(423).body(
                            AccountLockedResponse(lockedUntil = outcome.lockedUntil.toString()),
                        )
                }
            },
            onFailure = { error -> internalError("Login failed", error) },
        )

    @PostMapping("/register")
    fun register(@RequestBody request: SignUpRequest): ResponseEntity<Any> =
        authenticationService.register(request.username, request.password, request.email).fold(
            onSuccess = { user ->
                ResponseEntity.status(201).body(
                    UserResponse(
                        id = user.id?.toLong() ?: 0L,
                        username = user.username,
                        role = user.role.name,
                    ),
                )
            },
            onFailure = { error ->
                when (error) {
                    is Error.AlreadyExists ->
                        ResponseEntity.status(409).body(mapOf("error" to "User already exists"))
                    is Error.ValidationFailed -> ResponseEntity.status(400).body(
                        mapOf("error" to "Invalid input", "details" to error.details),
                    )
                    else -> {
                        logger.error("Registration failed for username='{}'", request.username, error)
                        ResponseEntity.status(500).body(mapOf("error" to "Registration failed"))
                    }
                }
            },
        )

    @PostMapping("/2fa/verify")
    fun verifyTwoFactor(@RequestBody request: VerifyTwoFactorRequest): ResponseEntity<Any> =
        authenticationService.verifyTwoFactor(request.challengeId, request.code).fold(
            onSuccess = { outcome ->
                when (outcome) {
                    is TwoFactorOutcome.Authenticated ->
                        ResponseEntity.ok(LoginResponse(token = outcome.token))

                    is TwoFactorOutcome.PasswordChangeRequired ->
                        ResponseEntity.ok(
                            PasswordChangeRequiredResponse(passwordChangeToken = outcome.passwordChangeToken),
                        )

                    is TwoFactorOutcome.InvalidCode ->
                        ResponseEntity.status(401).body(
                            InvalidCredentialsResponse(
                                error = "Invalid verification code",
                                attemptsRemaining = outcome.attemptsRemaining,
                            ),
                        )

                    is TwoFactorOutcome.ChallengeExpired ->
                        ResponseEntity.status(400).body(
                            mapOf("error" to "Verification challenge expired", "details" to outcome.reason),
                        )
                }
            },
            onFailure = { error -> internalError("Two-factor verification failed", error) },
        )

    @PostMapping("/password/change")
    fun changePassword(@RequestBody request: PasswordChangeRequest): ResponseEntity<Any> =
        authenticationService.changePassword(request.passwordChangeToken, request.newPassword).fold(
            onSuccess = { ResponseEntity.ok(MessageResponse("Password changed")) },
            onFailure = { error ->
                when (error) {
                    is Error.Unauthorized ->
                        ResponseEntity.status(401).body(mapOf("error" to "Invalid password change token"))
                    is Error.ValidationFailed -> ResponseEntity.status(400).body(
                        mapOf("error" to "Invalid input", "details" to error.details),
                    )
                    is Error.NotFound -> ResponseEntity.status(404).body(mapOf("error" to "User not found"))
                    else -> internalError("Password change failed", error)
                }
            },
        )

    /**
     * Запрос восстановления всегда отвечает 202, чтобы не раскрывать,
     * существует ли аккаунт с таким именем (защита от перебора пользователей).
     */
    @PostMapping("/recovery/request")
    fun requestRecovery(@RequestBody request: PasswordRecoveryRequest): ResponseEntity<Any> =
        authenticationService.requestRecovery(request.username).fold(
            onSuccess = {
                ResponseEntity.accepted().body(
                    MessageResponse("If the account exists, a recovery code has been sent"),
                )
            },
            onFailure = { error -> internalError("Recovery request failed", error) },
        )

    @PostMapping("/recovery/confirm")
    fun confirmRecovery(@RequestBody request: PasswordRecoveryConfirmRequest): ResponseEntity<Any> =
        authenticationService.confirmRecovery(request.username, request.code, request.newPassword).fold(
            onSuccess = { ResponseEntity.ok(MessageResponse("Password recovered")) },
            onFailure = { error ->
                when (error) {
                    is Error.Unauthorized ->
                        ResponseEntity.status(401).body(mapOf("error" to "Invalid recovery code"))
                    is Error.ValidationFailed -> ResponseEntity.status(400).body(
                        mapOf("error" to "Invalid input", "details" to error.details),
                    )
                    else -> internalError("Recovery failed", error)
                }
            },
        )

    private fun internalError(message: String, error: Throwable): ResponseEntity<Any> {
        logger.error("{}: {}", message, error.toString())
        return ResponseEntity.status(500).body(mapOf("error" to message))
    }
}
