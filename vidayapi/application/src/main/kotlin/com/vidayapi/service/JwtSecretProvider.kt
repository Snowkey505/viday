package com.vidayapi.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

/**
 * Единственный источник JWT-подписи для JwtService (application) и
 * JwtTokenProvider (infrastructure).
 *
 * Ключ НЕ хранится в репозитории: он берётся из `JWT_SECRET` (переменная
 * окружения / GitHub Actions secret). Если переменная не задана (локальный
 * запуск, тесты), генерируется одноразовый ключ на время жизни процесса.
 * Оба компонента получают один и тот же экземпляр бина, поэтому подпись и
 * проверка токенов согласованы.
 */
@Component
class JwtSecretProvider(
    @Value("\${jwt.secret:}") configuredSecret: String,
) {
    val secret: String = configuredSecret.trim().takeIf { it.isNotEmpty() }
        ?: run {
            val generated = ByteArray(64).also { SecureRandom().nextBytes(it) }
            logger.warn(
                "JWT_SECRET is not configured. Using an ephemeral signing key for this process. " +
                    "Set JWT_SECRET in CI/production; tokens will not survive a restart.",
            )
            Base64.getEncoder().encodeToString(generated)
        }

    private companion object {
        val logger = LoggerFactory.getLogger(JwtSecretProvider::class.java)
    }
}
