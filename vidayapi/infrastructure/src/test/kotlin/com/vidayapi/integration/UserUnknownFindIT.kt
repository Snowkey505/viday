package com.vidayapi.integration

import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * IT-сценарий (Требование 17a): поиск несуществующего пользователя возвращает
 * null, а не исключение (queryForObject -> EmptyResultDataAccessException обработан).
 */
@Epic("Integration (ЛР2)")
@Feature("Доступ к данным: JdbcUserRepository на реальном PostgreSQL")
class UserUnknownFindIT : StandPostgresIT() {

    private val repos = IntegrationRepositories(jdbc)

    @Test
    @Story("несуществующий пользователь")
    @Description("queryForObject бросает EmptyResultDataAccessException -> null, а не ошибка")
    fun `findByUsername for unknown user returns null`() {
        // Act
        val result = repos.users.findByUsername("nobody_it")

        // Assert
        assertThat(result.valueOrNull()).isNull()
    }
}