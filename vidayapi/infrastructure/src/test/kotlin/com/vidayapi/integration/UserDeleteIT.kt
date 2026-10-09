package com.vidayapi.integration

import com.vidayapi.model.Error
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * IT-сценарий (Требование 17a): удаление пользователя из viday."user" —
 * повторное удаление несуществующего -> Error.NotFound.
 */
@Epic("Integration (ЛР2)")
@Feature("Доступ к данным: JdbcUserRepository на реальном PostgreSQL")
class UserDeleteIT : StandPostgresIT() {

    private val repos = IntegrationRepositories(jdbc)

    @Test
    @Story("удаление пользователя")
    @Description("DELETE FROM viday.user WHERE id -> NotFound при повторном удалении")
    fun `deleteById removes user and is idempotent-aware`() {
        // Arrange
        val u = repos.register.execute("del_it", "p").valueOrThrow()

        // Act
        repos.users.deleteById(u.id!!).valueOrThrow()

        // Assert
        assertThat(repos.users.findById(u.id!!).valueOrThrow()).isNull()
        assertThat(repos.users.deleteById(u.id!!).exceptionOrNull())
            .isInstanceOf(Error.NotFound::class.java)
    }
}