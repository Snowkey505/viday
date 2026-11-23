package com.vidayapi.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Учётные данные аутентификации (ЛР5), отдельные от профиля [User].
 *
 * Наличие записи [AuthAccount] означает, что для пользователя включён
 * расширенный сценарий входа:
 *  * второй фактор по email;
 *  * лимит неудачных попыток и временная блокировка;
 *  * плановая ротация пароля.
 *
 * Пользователи без такой записи (созданные старым API без email) продолжают
 * входить по паролю — это сохраняет обратную совместимость ЛР2/ЛР4.
 */
data class AuthAccount(
    val userId: Int,
    val username: String,
    val passwordHash: String,
    val role: Role,
    val email: String,
    val twoFactorEnabled: Boolean,
    val failedLoginAttempts: Int,
    val lockedUntil: OffsetDateTime?,
    val passwordChangedAt: OffsetDateTime,
)

/** Назначение одноразового кода. */
enum class AuthChallengePurpose {
    LOGIN_2FA,
    PASSWORD_RECOVERY,
}

/**
 * Одноразовый код. В БД хранится только [codeHash] (BCrypt); сам код
 * передаётся пользователю письмом и повторно из БД не читается.
 */
data class AuthChallenge(
    val id: UUID,
    val userId: Int,
    val purpose: AuthChallengePurpose,
    val codeHash: String,
    val attempts: Int,
    val maxAttempts: Int,
    val expiresAt: OffsetDateTime,
    val consumedAt: OffsetDateTime? = null,
)

/** Состояние счётчика неудачных попыток после очередной ошибки пароля. */
data class AccountLockState(
    val failedAttempts: Int,
    val lockedUntil: OffsetDateTime?,
) {
    val isLocked: Boolean get() = lockedUntil != null
}
