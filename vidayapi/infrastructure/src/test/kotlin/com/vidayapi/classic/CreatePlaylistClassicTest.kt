package com.vidayapi.classic

import com.vidayapi.model.AccessType
import com.vidayapi.model.Error
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
@Feature("Создание плейлиста")
class CreatePlaylistClassicTest : PerTestJdbcFixture {

    override val db = ClassicJdbc()
    private val register = RegisterUserUseCase(db.users, mockPasswordEncoder())
    private val useCase = CreatePlaylistUseCase(db.playlists)

    @Test
    @Story("Плейлист пишется реальным JdbcPlaylistRepository и не дублируется по имени владельца")
    @Description(
        "Сценарий: регистрация владельца, создание PRIVATE плейлиста, повтор с тем же именем. " +
            "SQL INSERT/COUNT выполняются на подменённом соединении, без PostgreSQL.",
    )
    fun `creates playlist then rejects duplicate name for same owner`() {
        val owner = register.execute("owner", "password123").valueOrThrow()

        val playlist = useCase.execute("Night mix", owner.id!!, AccessType.PRIVATE).valueOrThrow()

        assertThat(playlist.id).isNotNull()
        assertThat(playlist.name).isEqualTo("Night mix")
        assertThat(playlist.ownerId).isEqualTo(owner.id)
        assertThat(playlist.accessType).isEqualTo(AccessType.PRIVATE)

        val loaded = db.playlists.findById(playlist.id!!).valueOrThrow()
        assertThat(loaded.name).isEqualTo("Night mix")
        assertThat(db.dataSource.catalog.rows("playlist")).hasSize(1)

        assertThat(useCase.execute("Night mix", owner.id!!, AccessType.PUBLIC).errorOrThrow())
            .isEqualTo(Error.AlreadyExists)
        assertThat(db.dataSource.catalog.rows("playlist")).hasSize(1)
    }

    @Test
    @Story("Пустое имя плейлиста отклоняется до обращения к БД")
    @Description(
        "Классический стиль: валидация в use case раньше SQL, " +
            "COUNT/INSERT не выполняются и в каталоге не появляется ни одной строки.",
    )
    fun `rejects blank playlist name without writing to database`() {
        assertThat(useCase.execute("   ", 3, AccessType.PUBLIC).errorOrThrow())
            .isEqualTo(Error.ValidationFailed("Playlist name cannot be blank"))
        assertThat(useCase.execute("", 3, AccessType.PRIVATE).errorOrThrow())
            .isEqualTo(Error.ValidationFailed("Playlist name cannot be blank"))

        assertThat(db.dataSource.catalog.rows("playlist")).isEmpty()
    }
}
