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
import org.springframework.jdbc.core.JdbcTemplate

object PlainPasswordEncoder : PasswordEncoder {
    override fun encode(rawPassword: String): String = "plain:$rawPassword"
    override fun matches(rawPassword: String, encodedPassword: String): Boolean =
        encodedPassword == "plain:$rawPassword"
}

/**
 * Комплект репозиториев поверх реального пула стенда.
 * Каждый IT-сценарий создаёт свой экземпляр от jdbc-хендла базового класса.
 */
class IntegrationRepositories(jdbc: JdbcTemplate) {
    val users = JdbcUserRepository(jdbc)
    val playlists = JdbcPlaylistRepository(jdbc, NOPLogger.NOP_LOGGER)
    val content = JdbcContentRepository(jdbc)
    val register = RegisterUserUseCase(users, PlainPasswordEncoder)

    fun owner(username: String): Int =
        register.execute(username, "pass123").getOrThrow().id!!

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
