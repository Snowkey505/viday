package com.vidayapi.dto.requests

/**
 * Регистрация пользователя.
 *
 * [email] необязателен для обратной совместимости с ЛР2/ЛР4: старые клиенты
 * не передают его и получают обычный вход по паролю. Если email указан,
 * включается 2FA по email и расширенные правила ЛР5.
 */
data class SignUpRequest(
    val username: String,
    val password: String,
    val email: String? = null,
)
