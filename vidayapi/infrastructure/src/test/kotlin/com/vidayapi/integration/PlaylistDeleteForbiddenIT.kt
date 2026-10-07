package com.vidayapi.integration

import com.vidayapi.model.AccessType
import com.vidayapi.model.Error
import com.vidayapi.model.Playlist
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * IT-сценарий (Требование 17a): удаление чужого плейлиста запрещено —
 * deletePlaylist проверяет владельца в коде репозитория -> Error.Forbidden.
 */
@Epic("Integration (ЛР2)")
@Feature("Доступ к данным: JdbcPlaylistRepository на реальном PostgreSQL")
class PlaylistDeleteForbiddenIT : StandPostgresIT() {

    private val repos = IntegrationRepositories(jdbc)

    @Test
    @Story("удаление чужого плейлиста")
    @Description("deletePlaylist проверяет владельца в коде репозитория -> Forbidden")
    fun `deletePlaylist by non-owner is Forbidden`() {
        // Arrange
        val ownerId = repos.owner("owner_it")
        val stranger = repos.register.execute("stranger_it", "p").valueOrThrow().id!!
        val playlist = repos.playlists.save(
            Playlist(name = "Private", ownerId = ownerId, accessType = AccessType.PRIVATE),
        ).valueOrThrow()

        // Act
        val result = repos.playlists.deletePlaylist(playlist.id!!, stranger)

        // Assert
        assertThat(result.exceptionOrNull()).isInstanceOf(Error.Forbidden::class.java)
        // Плейлист на месте
        assertThat(repos.playlists.findById(playlist.id!!).valueOrThrow().id).isEqualTo(playlist.id)
    }
}