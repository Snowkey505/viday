package com.vidayapi.classic

import com.vidayapi.model.Role
import com.vidayapi.usecase.RegisterUserUseCase
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * Демонстрация фикстур из [ClassicFixtures] — как «уметь» ими пользоваться.
 *
 * Обе стратегии из задания:
 *
 * - **А. На каждый тест** ([PerTestJdbcFixture]): фикстура запускается перед каждым
 *   тестом и затирает репозитории; после теста затирает их ещё раз и проверяет чистоту.
 * - **Б. Один раз на класс** ([SuiteJdbcFixture]): фикстура запускается в начале всех
 *   тестов, тесты прогоняются на общем состоянии, затем она всё зачищает.
 *
 * Эти классы помечены `@Tag("fixtures-demo")` и **исключены из обычного прогона**
 * (`test` отфильтровывает тег, поэтому число 22 теста не меняется). Запуск:
 *
 * ```
 * ./gradlew :infrastructure:testFixturesDemo      # или make test-fixtures-demo
 * ```
 */
@Tag("fixtures-demo")
@Epic("Classic (Detroit)")
@Feature("Фикстуры: стратегия А — на каждый тест")
@DisplayName("PerTestJdbcFixture: репозитории затираются до и после каждого теста")
class PerTestJdbcFixtureDemo : PerTestJdbcFixture {

    override val db = ClassicJdbc()

    private val register = RegisterUserUseCase(db.users, mockPasswordEncoder())

    @Test
    @DisplayName("тест 1: регистрация пишет строку в каталог")
    fun firstTestWritesUser() {
        val created = register.execute("alice", "secret-pass").valueOrThrow()

        assertThat(created.username).isEqualTo("alice")
        assertThat(created.role).isEqualTo(Role.USER)
        assertThat(db.dataSource.catalog.rows("user")).hasSize(1)
    }

    @Test
    @DisplayName("тест 2: фикстура уже затёрла данные теста 1 — каталог чистый")
    fun secondTestSeesCleanRepositories() {
        // Если бы фикстура не затёрла репозиторий после теста 1, имя "alice"
        // было бы занято и повторная регистрация вернула бы AlreadyExists.
        db.assertRepositoriesEmpty()

        val created = register.execute("alice", "secret-pass").valueOrThrow()

        assertThat(created.username).isEqualTo("alice")
        assertThat(db.dataSource.catalog.rows("user")).hasSize(1)
    }
}

@Tag("fixtures-demo")
@Epic("Classic (Detroit)")
@Feature("Фикстуры: стратегия Б — один раз на класс")
@DisplayName("SuiteJdbcFixture: репозитории затираются до и после всех тестов класса")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SuiteJdbcFixtureDemo : SuiteJdbcFixture {

    override val db = ClassicJdbc()

    private val register = RegisterUserUseCase(db.users, mockPasswordEncoder())

    @Test
    @Order(1)
    @DisplayName("тест 1: создаёт пользователя")
    fun firstTestCreatesUser() {
        register.execute("bob", "secret-pass").valueOrThrow()

        assertThat(db.dataSource.catalog.rows("user")).hasSize(1)
    }

    @Test
    @Order(2)
    @DisplayName("тест 2: видит данные теста 1 — состояние общее на весь класс")
    fun secondTestSeesSharedState() {
        // Между тестами фикстура НЕ затирает репозитории (стратегия Б):
        // строка, созданная в тесте 1, всё ещё на месте. @AfterAll зачистит всё
        // после последнего теста и проверит чистоту (см. SuiteJdbcFixture).
        assertThat(db.users.findByUsername("bob").valueOrThrow()).isNotNull()
        assertThat(db.dataSource.catalog.rows("user")).hasSize(1)
    }
}