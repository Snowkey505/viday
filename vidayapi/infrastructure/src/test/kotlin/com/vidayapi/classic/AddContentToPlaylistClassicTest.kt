package com.vidayapi.classic

import com.vidayapi.model.AccessType
import com.vidayapi.model.Content
import com.vidayapi.model.ContentType
import com.vidayapi.model.Error
import com.vidayapi.usecase.AddContentToPlaylistUseCase
import com.vidayapi.usecase.CreatePlaylistUseCase
import com.vidayapi.usecase.RegisterUserUseCase
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("offline")
@Epic("Classic (Detroit)")
@Feature("Добавление в плейлист")
class AddContentToPlaylistClassicTest : PerTestJdbcFixture {

    override val db = ClassicJdbc()
    private val register = RegisterUserUseCase(db.users, mockPasswordEncoder())
    private val createPlaylist = CreatePlaylistUseCase(db.playlists)
    private val useCase = AddContentToPlaylistUseCase(db.playlists)

    @Test
    @Story("Владелец добавляет ролики с автопозицией; чужой пользователь получает Forbidden")
    @Description(
        "Цепочка реальных JDBC-репозиториев: user → playlist → content → content_to_playlist. " +
            "COUNT(*) задаёт позицию, повтор чужим ownerId не пишет вторую связь.",
    )
    fun `owner appends items by auto position and stranger is forbidden`() {
        val owner = register.execute("dj", "password123").valueOrThrow()
        val stranger = register.execute("fan", "password123").valueOrThrow()
        val playlist = createPlaylist.execute("Set", owner.id!!, AccessType.PUBLIC).valueOrThrow()
        val first = db.content.save(
            Content(type = ContentType.VIDEO, name = "Track 1", description = null, source = null, ownerId = owner.id!!, accessType = AccessType.PUBLIC),
        ).valueOrThrow()
        val second = db.content.save(
            Content(type = ContentType.VIDEO, name = "Track 2", description = null, source = null, ownerId = owner.id!!, accessType = AccessType.PUBLIC),
        ).valueOrThrow()

        val item1 = useCase.execute(owner.id!!, playlist.id!!, first.id!!, position = null).valueOrThrow()
        val item2 = useCase.execute(owner.id!!, playlist.id!!, second.id!!, position = null).valueOrThrow()

        assertThat(item1.position).isEqualTo(1)
        assertThat(item2.position).isEqualTo(2)
        assertThat(db.dataSource.catalog.rows("content_to_playlist")).hasSize(2)

        assertThat(useCase.execute(stranger.id!!, playlist.id!!, first.id!!, null).errorOrThrow())
            .isEqualTo(Error.Forbidden)
        assertThat(db.dataSource.catalog.rows("content_to_playlist")).hasSize(2)
    }
}
