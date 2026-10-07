package com.vidayapi.integration

import com.vidayapi.model.AccessType
import com.vidayapi.model.Playlist
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * IT-сценарий (Требование 17a): создание плейлиста (INSERT с RETURNING id)
 * и поиск по id на реальном PostgreSQL стенда.
 */
@Epic("Integration (ЛР2)")
@Feature("Доступ к данным: JdbcPlaylistRepository на реальном PostgreSQL")
class PlaylistSaveFindIT : StandPostgresIT() {

    private val repos = IntegrationRepositories(jdbc)

    @Test
    @Story("save -> findById")
    @Description("INSERT playlist c RETURNING id и SELECT с JOIN на viday.access_type")
    fun `save and findById round-trip`() {
        // Arrange
        val ownerId = repos.owner("owner_it")

        // Act
        val saved = repos.playlists.save(
            Playlist(name = "My list", ownerId = ownerId, accessType = AccessType.PUBLIC),
        ).valueOrThrow()
        val found = repos.playlists.findById(saved.id!!).valueOrThrow()

        // Assert
        assertThat(found.id).isEqualTo(saved.id)
        assertThat(found.name).isEqualTo("My list")
        assertThat(found.ownerId).isEqualTo(ownerId)
        assertThat(found.accessType).isEqualTo(AccessType.PUBLIC)
    }
}