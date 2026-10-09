package com.vidayapi.integration

import com.vidayapi.model.Error
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * IT-сценарий (Требование 17a): уникальный констрейнт на username не даёт
 * зарегистрировать дубликат — возвращается Error.AlreadyExists.
 */
@Epic("Integration (ЛР2)")
@Feature("Доступ к данным: JdbcUserRepository на реальном PostgreSQL")
class UserDuplicateUsernameIT : StandPostgresIT() {

    private val repos = IntegrationRepositories(jdbc)

    @Test
    @Story("дубликат username")
    @Description("Уникальный констрейнт на username -> Error.AlreadyExists")
    fun `duplicate username returns AlreadyExists`() {
        // Arrange
        repos.register.execute("bob_it", "secret123").valueOrThrow()

        // Act
        val second = repos.register.execute("bob_it", "other")

        // Assert
        assertThat(second.exceptionOrNull()).isInstanceOf(Error.AlreadyExists::class.java)
    }
}