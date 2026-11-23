package com.vidayapi.dto.responses

/** Второй фактор включён: JWT ещё не выдан, нужен код из письма. */
data class TwoFactorChallengeResponse(
    val twoFactorRequired: Boolean = true,
    val challengeId: String,
    val channel: String = "EMAIL",
    val emailMasked: String,
)

/** Пароль просрочен: выдан ограниченный токен только для смены пароля. */
data class PasswordChangeRequiredResponse(
    val passwordChangeRequired: Boolean = true,
    val passwordChangeToken: String,
)

/** Ответ на неверный пароль: сколько попыток осталось до блокировки. */
data class InvalidCredentialsResponse(
    val error: String = "Invalid credentials",
    val attemptsRemaining: Int,
)

/** Аккаунт временно заблокирован после превышения лимита попыток. */
data class AccountLockedResponse(
    val error: String = "Account locked",
    val lockedUntil: String,
    val attemptsRemaining: Int = 0,
)

data class MessageResponse(
    val message: String,
)
