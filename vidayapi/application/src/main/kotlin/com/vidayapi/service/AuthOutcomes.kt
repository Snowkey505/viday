package com.vidayapi.service

import java.time.OffsetDateTime

/** Результат первой стадии входа (пароль). */
sealed interface LoginOutcome {
    data class Authenticated(val token: String) : LoginOutcome
    data class TwoFactorRequired(
        val challengeId: String,
        val channel: String,
        val emailMasked: String,
    ) : LoginOutcome

    data class PasswordChangeRequired(val passwordChangeToken: String) : LoginOutcome
    data class InvalidCredentials(val attemptsRemaining: Int) : LoginOutcome
    data class Locked(val lockedUntil: OffsetDateTime) : LoginOutcome
}

/** Результат проверки одноразового кода из письма. */
sealed interface TwoFactorOutcome {
    data class Authenticated(val token: String) : TwoFactorOutcome
    data class PasswordChangeRequired(val passwordChangeToken: String) : TwoFactorOutcome
    data class InvalidCode(val attemptsRemaining: Int) : TwoFactorOutcome
    data class ChallengeExpired(val reason: String) : TwoFactorOutcome
}
