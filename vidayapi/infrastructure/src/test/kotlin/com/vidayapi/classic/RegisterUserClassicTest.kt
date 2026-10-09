package com.vidayapi.classic

import com.vidayapi.model.Error
import com.vidayapi.model.Role
import com.vidayapi.usecase.RegisterUserUseCase
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("offline")
@Epic("Classic (Detroit)")
@Feature("Регистрация пользователя")
class RegisterUserClassicTest : PerTestJdbcFixture {

    override val db = ClassicJdbc()
    private val encoder = mockPasswordEncoder()
    private val useCase = RegisterUserUseCase(db.users, encoder)

    @Test
    @Story("Уникальный username сохраняется через реальный JdbcUserRepository")
    @Description(
        "Классический стиль: production JdbcUserRepository + подмена JDBC-соединения. " +
            "Пароль кодируется mockk-заглушкой (без реального BCrypt), повторная регистрация " +
            "того же имени даёт AlreadyExists, в каталоге остаётся одна строка.",
    )
    fun `registers user then rejects duplicate username`() {
        val created = useCase.execute("alice", "secret-pass").valueOrThrow()

        assertThat(created.id).isNotNull()
        assertThat(created.username).isEqualTo("alice")
        assertThat(created.role).isEqualTo(Role.USER)
        assertThat(encoder.matches("secret-pass", created.passwordHash)).isTrue()

        val stored = db.users.findByUsername("alice").valueOrThrow()
        assertThat(stored?.id).isEqualTo(created.id)
        assertThat(db.dataSource.catalog.rows("user")).hasSize(1)

        assertThat(useCase.execute("alice", "other").errorOrThrow()).isEqualTo(Error.AlreadyExists)
        assertThat(db.dataSource.catalog.rows("user")).hasSize(1)
    }

    @Test
    @Story("Пустые username/password отклоняются до обращения к БД")
    @Description(
        "Классический стиль: валидация выполняется в use case раньше SQL, " +
            "INSERT не выполняется и в каталоге не появляется ни одной строки.",
    )
    fun `rejects blank credentials without writing to database`() {
        assertThat(useCase.execute("", "secret-pass").errorOrThrow())
            .isEqualTo(Error.ValidationFailed("Username/password blank"))
        assertThat(useCase.execute("alice", " ").errorOrThrow())
            .isEqualTo(Error.ValidationFailed("Username/password blank"))
        assertThat(useCase.execute("   ", "").errorOrThrow())
            .isEqualTo(Error.ValidationFailed("Username/password blank"))

        assertThat(db.dataSource.catalog.rows("user")).isEmpty()
    }
}
