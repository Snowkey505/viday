package com.vidayapi.integration

import com.vidayapi.model.Role
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@Epic("Integration")
@Feature("Доступ к данным: JdbcUserRepository на реальном PostgreSQL")
class UserSaveFindIT : StandPostgresIT() {

    private val repos = IntegrationRepositories(jdbc)

    @Test
    @Story("save -> findByUsername")
    @Description("Реальный INSERT с RETURNING id и SELECT с JOIN на viday.role")
    fun `save then findByUsername returns persisted user with role`() {
        // Arrange
        val saved = repos.register.execute("alice_it", "secret123").valueOrThrow()

        // Act
        val found = repos.users.findByUsername("alice_it").valueOrThrow()

        // Assert
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(saved.id)
        assertThat(found.username).isEqualTo("alice_it")
        assertThat(found.role).isEqualTo(Role.USER)
    }
}
