package com.vidayapi.integration

import com.vidayapi.model.AccessType
import com.vidayapi.model.Content
import com.vidayapi.model.ContentType
import com.vidayapi.model.Error
import com.vidayapi.repository.JdbcContentRepository
import com.vidayapi.repository.JdbcPlaylistRepository
import com.vidayapi.repository.JdbcUserRepository
import com.vidayapi.usecase.AddContentToPlaylistUseCase
import com.vidayapi.usecase.CreatePlaylistUseCase
import com.vidayapi.usecase.FollowUserUseCase
import com.vidayapi.usecase.RegisterUserUseCase
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.helpers.NOPLogger

/**
 * Интеграционные тесты БИЗНЕС-ЛОГИКИ (use case'ы) поверх РЕАЛЬНЫХ репозиториев
 * и реального PostgreSQL тестового стенда (Требование 2: компоненты доступа
 * к данным + бизнес-логика).
 *
 * Сценарии покрывают типовые действия пользователя: регистрация, публикация
 * контента, создание плейлиста, добавление контента, подписка — и их инварианты
 * (дубликаты, права, видимость).
 */
@Epic("Integration (ЛР2)")
@Feature("Бизнес-логика на реальных репозиториях")
class BusinessFlowIT : StandPostgresIT() {

    private val users = JdbcUserRepository(jdbc)
    private val playlists = JdbcPlaylistRepository(jdbc, NOPLogger.NOP_LOGGER)
    private val content = JdbcContentRepository(jdbc)

    private val register = RegisterUserUseCase(users, PlainPasswordEncoder)
    private val createPlaylist = CreatePlaylistUseCase(playlists)
    private val addContent = AddContentToPlaylistUseCase(playlists)
    private val follow = FollowUserUseCase(users)

    @Test
    @Story("публикация видео + публичный плейлист + подписка")
    @Description(
        "Полный бизнес-сценарий: регистрация креатора и подписчика, публикация контента, " +
            "создание публичного плейлиста, добавление контента, подписка; " +
            "проверяется видимость плейлиста у подписчика и инварианты (дубликат имени, чужие права).",
    )
    fun `creator publishes to public playlist and follower sees it`() {
        // Arrange: два пользователя
        val creator = register.execute("creator_flow_it", "pass123").valueOrThrow()
        val follower = register.execute("follower_flow_it", "pass123").valueOrThrow()

        // Act: креатор публикует контент и собирает публичный плейлист
        val video = content.save(
            Content(
                type = ContentType.VIDEO,
                name = "Flow clip",
                description = "integration flow",
                source = null,
                ownerId = creator.id!!,
                accessType = AccessType.PUBLIC,
            ),
        ).valueOrThrow()

        val playlist = createPlaylist.execute("Flow playlist", creator.id!!, AccessType.PUBLIC).valueOrThrow()
        addContent.execute(creator.id!!, playlist.id!!, video.id!!, position = null).valueOrThrow()
        follow.execute(follower.id!!, creator.id!!).valueOrThrow()

        // Assert: подписчик видит публичный плейлист креатора
        val available = playlists.findAvailablePlaylists(follower.id!!).valueOrThrow()
        assertThat(available.map { it.id }).contains(playlist.id)
        assertThat(playlists.countItemsInPlaylist(playlist.id!!).valueOrThrow()).isEqualTo(1)

        // Инвариант 1: имя плейлиста уникально в рамках владельца
        val duplicate = createPlaylist.execute("Flow playlist", creator.id!!, AccessType.PUBLIC)
        assertThat(duplicate.exceptionOrNull()).isInstanceOf(Error.AlreadyExists::class.java)

        // Инвариант 2: подписчик не может добавить контент в чужой плейлист
        val forbidden = addContent.execute(follower.id!!, playlist.id!!, video.id!!, position = null)
        assertThat(forbidden.exceptionOrNull()).isInstanceOf(Error.Forbidden::class.java)

        // Инвариант 3: повторная подписка запрещена
        val secondFollow = follow.execute(follower.id!!, creator.id!!)
        assertThat(secondFollow.exceptionOrNull()).isInstanceOf(Error.UserAlreadyFollowed::class.java)
    }
}