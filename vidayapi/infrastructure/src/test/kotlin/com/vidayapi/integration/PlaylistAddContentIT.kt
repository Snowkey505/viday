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

@Epic("Integration")
@Feature("Доступ к данным: JdbcPlaylistRepository на реальном PostgreSQL")
class PlaylistAddContentIT : StandPostgresIT() {

    private val repos = IntegrationRepositories(jdbc)

    @Test
    @Story("добавление контента в плейлист")
    @Description("addContentToPlaylist -> countItemsInPlaylist растёт; PK (content_id, playlist_id) не пускает дубликат")
    fun `addContentToPlaylist persists item and increments count`() {
        // Arrange (17b: arrange один раз в начале сценария)
        val ownerId = repos.owner("owner_it")
        val video1 = repos.saveVideo(ownerId)
        val video2 = repos.saveVideo(ownerId)
        val playlist = repos.playlists.save(
            Playlist(name = "To watch", ownerId = ownerId, accessType = AccessType.PUBLIC),
        ).valueOrThrow()

        // Act
        repos.playlists.addContentToPlaylist(video1.id!!, playlist.id!!, position = 1).valueOrThrow()
        repos.playlists.addContentToPlaylist(video2.id!!, playlist.id!!, position = 2).valueOrThrow()

        // Assert
        assertThat(repos.playlists.countItemsInPlaylist(playlist.id!!).valueOrThrow()).isEqualTo(2)

        // Дубликат того же контента запрещён PK -> PlaylistPositionConflict
        val duplicate = repos.playlists.addContentToPlaylist(video1.id!!, playlist.id!!, position = 3)
        assertThat(duplicate.exceptionOrNull())
            .isInstanceOf(Error.PlaylistPositionConflict::class.java)

        repos.playlists.removeContentFromPlaylist(video1.id!!, playlist.id!!).valueOrThrow()
        assertThat(repos.playlists.countItemsInPlaylist(playlist.id!!).valueOrThrow()).isEqualTo(1)
    }
}
