package com.vidayapi.bdd

import org.springframework.jdbc.core.JdbcTemplate

/**
 * Прямой доступ к тестовому стенду для подготовки данных и проверки
 * постусловий. Используется только тестовой обвязкой (хуки/шаги), не
 * приложением.
 *
 * Это «административный» доступ к PostgreSQL тестового стенда: приложение
 * в это время ходит под ограниченной ролью viday_auth (см. RlsContext).
 */
class BddSupport(private val jdbcTemplate: JdbcTemplate) {

    /** Код из письма, привязанного к конкретному challenge. */
    fun readChallengeCode(challengeId: String): String {
        val body = jdbcTemplate.query(
            """
            SELECT body FROM viday.auth_email_outbox
            WHERE challenge_id = ?::uuid
            ORDER BY id DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getString("body") },
            challengeId,
        ).firstOrNull() ?: error("Письмо для challenge $challengeId не найдено")

        return extractCode(body)
    }

    /** Код последнего письма восстановления доступа для email. */
    fun readRecoveryCode(email: String): String {
        val body = jdbcTemplate.query(
            """
            SELECT o.body
            FROM viday.auth_email_outbox o
            JOIN viday.auth_challenge c ON c.id = o.challenge_id
            WHERE o.recipient = ? AND c.purpose = 'PASSWORD_RECOVERY'
            ORDER BY o.id DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getString("body") },
            email,
        ).firstOrNull() ?: error("Письмо восстановления для $email не найдено")

        return extractCode(body)
    }

    /** Имитация плановой ротации: сдвигаем дату смены пароля в прошлое. */
    fun expirePassword(username: String, days: Int) {
        val updated = jdbcTemplate.update(
            """
            UPDATE viday.user_auth a
            SET password_changed_at = CURRENT_TIMESTAMP - make_interval(days => ?)
            FROM viday."user" u
            WHERE u.id = a.user_id AND u.username = ?
            """.trimIndent(),
            days,
            username,
        )
        check(updated > 0) { "Не удалось изменить дату смены пароля для $username" }
    }

    fun userExists(username: String): Boolean =
        jdbcTemplate.query(
            "SELECT 1 FROM viday.\"user\" WHERE username = ?",
            { rs, _ -> rs.getInt(1) },
            username,
        ).isNotEmpty()

    fun roleOf(username: String): String? =
        jdbcTemplate.query(
            """
            SELECT r.name
            FROM viday."user" u
            JOIN viday.role r ON r.id = u.role_id
            WHERE u.username = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("name") },
            username,
        ).firstOrNull()

    fun deleteUserByUsername(username: String) {
        // user_auth/auth_challenge/auth_email_outbox удаляются каскадом.
        jdbcTemplate.update("DELETE FROM viday.\"user\" WHERE username = ?", username)
    }

    /**
     * Код берётся строго из строки «Код для <действие>: 123456», а не как
     * первое шестизначное число в письме. Имя технического пользователя
     * содержит случайные hex-символы и иногда само образует шесть цифр
     * подряд — «жадный» поиск по всему телу письма давал ложные срабатывания
     * и делал сценарий нестабильным.
     */
    private fun extractCode(body: String): String =
        Regex("""Код для [^:\r\n]+:\s*(\d{6})""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?: error("В письме не найден шестизначный код")
}
