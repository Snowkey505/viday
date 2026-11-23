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
 * IT-сценарий (Требование 17a): проверка существования плейлиста по
 * (name, ownerId) — COUNT(*) отражает реальное состояние таблицы,
 * один и тот же name у разных владельцев не конфликтует.
 */
@Epic("Integration (ЛР2)")
@Feature("Доступ к данным: JdbcPlaylistRepository на реальном PostgreSQL")
class PlaylistExistsByNameIT : StandPostgresIT() {

    private val repos = IntegrationRepositories(jdbc)

    @Test
    @Story("existsByNameAndOwnerId")
    @Description("COUNT(*) по name+owner_id отражает реальное состояние таблицы")
    fun `existsByNameAndOwnerId reflects saved rows`() {
        // Arrange
        val ownerId = repos.owner("owner_it")

        // Assert: до сохранения — false
        assertThat(repos.playlists.existsByNameAndOwnerId("Unique name", ownerId).valueOrThrow()).isFalse()

        // Act
        repos.playlists.save(
            Playlist(name = "Unique name", ownerId = ownerId, accessType = AccessType.PRIVATE),
        ).valueOrThrow()

        // Assert: после сохранения — true; тот же name у другого владельца — не конфликт
        assertThat(repos.playlists.existsByNameAndOwnerId("Unique name", ownerId).valueOrThrow()).isTrue()
        val other = repos.register.execute("owner2_it", "p").valueOrThrow().id!!
        assertThat(repos.playlists.existsByNameAndOwnerId("Unique name", other).valueOrThrow()).isFalse()
    }
}