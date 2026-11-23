package com.vidayapi.usecase

import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.model.AccessType
import com.vidayapi.model.Error
import com.vidayapi.model.Playlist
import com.vidayapi.port.PlaylistRepository
import com.vidayapi.support.assertLeftType
import com.vidayapi.support.assertRight
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

@Tag("offline")
@Epic("London")
@Feature("Создание плейлиста")
class CreatePlaylistLondonTest {

    private val playlists = mockk<PlaylistRepository>()
    private val useCase = CreatePlaylistUseCase(playlists)

    @Test
    @Story("existsByNameAndOwnerId затем save; дубликат имени не вызывает save")
    @Description("Лондон: взаимодействие с портом PlaylistRepository, без JDBC и без in-memory fake repository.")
    fun `execute saves only when the name is free`() {
        val saved = Playlist(9, "Night mix", 3, AccessType.PRIVATE, OffsetDateTime.parse("2026-01-01T00:00:00Z"))
        every { playlists.existsByNameAndOwnerId("Night mix", 3) } returns false.right()
        every { playlists.save(any()) } returns saved.right()

        val result = useCase.execute("Night mix", 3, AccessType.PRIVATE).assertRight()

        assertThat(result.id).isEqualTo(9)
        verifyOrder {
            playlists.existsByNameAndOwnerId("Night mix", 3)
            playlists.save(match { it.name == "Night mix" && it.ownerId == 3 && it.accessType == AccessType.PRIVATE })
        }

        every { playlists.existsByNameAndOwnerId("Night mix", 3) } returns true.right()
        useCase.execute("Night mix", 3, AccessType.PRIVATE).assertLeftType<Error.AlreadyExists>()
        verify(exactly = 1) { playlists.save(any()) }
    }

    @Test
    @Story("Пустое имя плейлиста - ValidationFailed без обращения к PlaylistRepository")
    @Description("Лондон: валидация происходит раньше existsByNameAndOwnerId и save.")
    fun `rejects blank name without touching repository`() {
        useCase.execute(" ", 3, AccessType.PRIVATE).assertLeftType<Error.ValidationFailed>()
        useCase.execute("", 3, AccessType.PUBLIC).assertLeftType<Error.ValidationFailed>()

        verify(exactly = 0) { playlists.existsByNameAndOwnerId(any(), any()) }
        verify(exactly = 0) { playlists.save(any()) }
    }

    @Test
    @Story("Ошибка existsByNameAndOwnerId пробрасывается, save не вызывается")
    @Description("Лондон: Left от порта прерывает цепочку через flatMap до сохранения.")
    fun `propagates repository failure from existence check`() {
        every { playlists.existsByNameAndOwnerId("Night mix", 3) } returns Error.InvalidInput.left()

        useCase.execute("Night mix", 3, AccessType.PRIVATE).assertLeftType<Error.InvalidInput>()

        verify(exactly = 0) { playlists.save(any()) }
    }

    @Test
    @Story("Ошибка save пробрасывается наружу как есть")
    @Description("Лондон: при свободном имени отказ хранилища возвращается без маскировки.")
    fun `propagates repository failure from save`() {
        every { playlists.existsByNameAndOwnerId("Night mix", 3) } returns false.right()
        every { playlists.save(any()) } returns Error.StorageFailure.left()

        useCase.execute("Night mix", 3, AccessType.PRIVATE).assertLeftType<Error.StorageFailure>()
    }
}
