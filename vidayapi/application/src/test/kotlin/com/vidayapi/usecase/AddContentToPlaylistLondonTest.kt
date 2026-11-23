package com.vidayapi.usecase

import com.vidayapi.model.right
import com.vidayapi.model.AccessType
import com.vidayapi.model.Error
import com.vidayapi.model.Playlist
import com.vidayapi.model.PlaylistItem
import com.vidayapi.port.PlaylistRepository
import com.vidayapi.support.assertLeftType
import com.vidayapi.support.assertRight
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
@Feature("Добавление в плейлист")
class AddContentToPlaylistLondonTest {

    private val playlists = mockk<PlaylistRepository>()
    private val useCase = AddContentToPlaylistUseCase(playlists)
    private val owned = Playlist(5, "Set", 10, AccessType.PUBLIC, OffsetDateTime.parse("2026-01-01T00:00:00Z"))

    @Test
    @Story("Владелец: findById затем addContentToPlaylist")
    @Description("Мок репозитория проверяет контракт use case")
    fun `execute adds for owner`() {
        every { playlists.findById(5) } returns owned.right()
        every { playlists.addContentToPlaylist(100, 5, null) } returns PlaylistItem(100, 5, 1).right()

        val item = useCase.execute(ownerId = 10, playlistId = 5, contentId = 100, position = null).assertRight()
        assertThat(item.position).isEqualTo(1)
        verify {
            playlists.findById(5)
            playlists.addContentToPlaylist(100, 5, null)
        }
    }
}
