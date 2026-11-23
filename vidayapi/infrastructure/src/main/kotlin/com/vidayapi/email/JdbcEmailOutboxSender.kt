package com.vidayapi.email

import com.vidayapi.model.AuthChallengePurpose
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.port.EmailSenderPort
import com.vidayapi.port.VerificationEmail
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * «Почтовый транспорт» тестового стенда (ЛР5).
 *
 * Письмо с кодом складывается в viday.auth_email_outbox. E2E-тест читает
 * последнее письмо для своего технического пользователя и берёт оттуда код.
 * Это детерминированная замена внешнего SMTP для CI.
 *
 * В production адаптер заменяется на SMTP/провайдера рассылки; контракт
 * [EmailSenderPort] при этом не меняется. Код в лог НЕ пишется.
 */
@Component
class JdbcEmailOutboxSender(
    private val jdbcTemplate: JdbcTemplate,
) : EmailSenderPort {

    private val logger = LoggerFactory.getLogger(JdbcEmailOutboxSender::class.java)

    override fun sendVerificationCode(message: VerificationEmail): Result<Unit> {
        val subject = when (message.purpose) {
            AuthChallengePurpose.LOGIN_2FA -> "Viday: код подтверждения входа"
            AuthChallengePurpose.PASSWORD_RECOVERY -> "Viday: восстановление доступа"
        }
        val action = when (message.purpose) {
            AuthChallengePurpose.LOGIN_2FA -> "входа в аккаунт"
            AuthChallengePurpose.PASSWORD_RECOVERY -> "восстановления доступа"
        }
        val body = """
            Здравствуйте, ${message.username}!

            Код для $action: ${message.code}

            Если вы не запрашивали код, просто проигнорируйте письмо.
            Никому не сообщайте этот код.
        """.trimIndent()

        return try {
            jdbcTemplate.update(
                """
                INSERT INTO viday.auth_email_outbox (challenge_id, recipient, subject, body)
                VALUES (?::uuid, ?, ?, ?)
                """.trimIndent(),
                message.challengeId.toString(),
                message.recipient,
                subject,
                body,
            )
            // В лог попадают только метаданные: ни код, ни тело письма.
            logger.info(
                "Auth email queued: recipient={}, purpose={}, challenge={}",
                message.recipient,
                message.purpose,
                message.challengeId,
            )
            Unit.right()
        } catch (e: DataAccessException) {
            logger.error("Failed to queue auth email for recipient={}", message.recipient, e)
            Error.StorageFailure.left()
        }
    }
}
