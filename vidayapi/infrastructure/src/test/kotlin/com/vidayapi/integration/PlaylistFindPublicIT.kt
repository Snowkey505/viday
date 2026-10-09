package com.vidayapi.integration

import com.vidayapi.model.AccessType
import com.vidayapi.model.Playlist
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * IT-сценарий (Требование 17a): findPublicPlaylists возвращает только PUBLIC
 * плейлисты и не зависит от владельца.
 */
@Epic("Integration (ЛР2)")
@Feature("Доступ к данным: JdbcPlaylistRepository на реальном PostgreSQL")
class PlaylistFindPublicIT : StandPostgresIT() {

    private val repos = IntegrationRepositories(jdbc)

    @Test
    @Story("фильтр публичных плейлистов")
    @Description("findPublicPlaylists возвращает только PUBLIC и не зависит от владельца")
    fun `findPublicPlaylists returns only PUBLIC entries`() {
        // Arrange
        val ownerId = repos.owner("owner_it")
        repos.playlists.save(
            Playlist(name = "Public A", ownerId = ownerId, accessType = AccessType.PUBLIC, createdAt = OffsetDateTime.now()),
        ).valueOrThrow()
        repos.playlists.save(
            Playlist(name = "Public B", ownerId = ownerId, accessType = AccessType.PUBLIC, createdAt = OffsetDateTime.now()),
        ).valueOrThrow()
        repos.playlists.save(
            Playlist(name = "Hidden", ownerId = ownerId, accessType = AccessType.PRIVATE, createdAt = OffsetDateTime.now()),
        ).valueOrThrow()

        // Act
        val names = repos.playlists.findPublicPlaylists().valueOrThrow().map { it.name }

        // Assert
        assertThat(names).containsExactlyInAnyOrder("Public A", "Public B")
    }
}