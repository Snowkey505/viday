package com.vidayapi.integration

import com.vidayapi.model.AccessType
import com.vidayapi.model.Content
import com.vidayapi.model.ContentType
import com.vidayapi.port.PasswordEncoder
import com.vidayapi.repository.JdbcContentRepository
import com.vidayapi.repository.JdbcPlaylistRepository
import com.vidayapi.repository.JdbcUserRepository
import com.vidayapi.usecase.RegisterUserUseCase
import org.slf4j.helpers.NOPLogger

/**
 * Общие фикстуры интеграционных тестов ЛР2 (Требование 17b: arrange один раз,
 * хелперы общие для всех IT-сценариев).
 *
 * [PlainPasswordEncoder] — детерминированный фейк [PasswordEncoder]: не выполняет
 * реальный BCrypt (это криптография, а не тестируемая логика), только помечает хеш.
 */
object PlainPasswordEncoder : PasswordEncoder {
    override fun encode(rawPassword: String): String = "plain:$rawPassword"
    override fun matches(rawPassword: String, encodedPassword: String): Boolean =
        encodedPassword == "plain:$rawPassword"
}

/**
 * Комплект репозиториев поверх реального пула стенда (см. [StandPostgresIT]).
 * Каждый IT-сценарий создаёт свой экземпляр от jdbc-хендла базового класса.
 */
class IntegrationRepositories(jdbc: org.springframework.jdbc.core.JdbcTemplate) {
    val users = JdbcUserRepository(jdbc)
    val playlists = JdbcPlaylistRepository(jdbc, NOPLogger.NOP_LOGGER)
    val content = JdbcContentRepository(jdbc)
    val register = RegisterUserUseCase(users, PlainPasswordEncoder)

    /** Регистрирует пользователя и возвращает его id (arrange одного шага). */
    fun owner(username: String): Int =
        register.execute(username, "pass123").getOrThrow().id!!

    /** Сохраняет публичное видео от имени [ownerId] и возвращает сохранённый [Content]. */
    fun saveVideo(ownerId: Int, name: String = "Clip ${System.nanoTime()}"): Content =
        content.save(
            Content(
                type = ContentType.VIDEO,
                name = name,
                description = "integration fixture",
                source = null,
                ownerId = ownerId,
                accessType = AccessType.PUBLIC,
            ),
        ).getOrThrow()
}