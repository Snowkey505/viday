package com.vidayapi.usecase

import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.model.Error
import com.vidayapi.model.Role
import com.vidayapi.model.User
import com.vidayapi.port.PasswordEncoder
import com.vidayapi.port.UserRepository
import com.vidayapi.support.assertLeftType
import com.vidayapi.support.assertRight
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

@Tag("offline")
@Epic("London")
@Feature("Регистрация пользователя")
class RegisterUserLondonTest {

    private val users = mockk<UserRepository>()
    private val encoder = mockk<PasswordEncoder>()
    private val useCase = RegisterUserUseCase(users, encoder)

    @Test
    @Story("existsByUsername → encode → save; занятое имя не кодирует пароль")
    @Description("Моки портов: сохраняется пользователь только с уже закодированным паролем.")
    fun `execute encodes password and saves unique user`() {
        val saved = User(11, "neo", "hashed", Role.USER, OffsetDateTime.parse("2026-01-01T00:00:00Z"))
        every { users.existsByUsername("neo") } returns false.right()
        every { encoder.encode("matrix") } returns "hashed"
        every { users.save(any()) } returns saved.right()

        val user = useCase.execute("neo", "matrix").assertRight()
        assertThat(user.id).isEqualTo(11)
        verifyOrder {
            users.existsByUsername("neo")
            encoder.encode("matrix")
            users.save(match { it.username == "neo" && it.passwordHash == "hashed" && it.role == Role.USER })
        }

        every { users.existsByUsername("neo") } returns true.right()
        useCase.execute("neo", "matrix").assertLeftType<Error.AlreadyExists>()
        verify(exactly = 1) { encoder.encode(any()) }
        verify(exactly = 1) { users.save(any()) }
    }

    @Test
    @Story("Пустые username/password — ValidationFailed без обращения к портам")
    @Description("Лондон: валидация выполняется раньше любого взаимодействия с UserRepository и PasswordEncoder.")
    fun `rejects blank username and blank password without touching ports`() {
        useCase.execute("", "matrix").assertLeftType<Error.ValidationFailed>()
        useCase.execute("neo", " ").assertLeftType<Error.ValidationFailed>()
        useCase.execute("   ", "").assertLeftType<Error.ValidationFailed>()

        verify(exactly = 0) { users.existsByUsername(any()) }
        verify(exactly = 0) { encoder.encode(any()) }
        verify(exactly = 0) { users.save(any()) }
    }

    @Test
    @Story("Ошибка existsByUsername пробрасывается, пароль не кодируется и save не вызывается")
    @Description("Лондон: Left от порта прерывает цепочку через flatMap до кодирования пароля.")
    fun `propagates repository failure from existence check`() {
        every { users.existsByUsername("neo") } returns Error.InvalidInput.left()

        useCase.execute("neo", "matrix").assertLeftType<Error.InvalidInput>()

        verify(exactly = 0) { encoder.encode(any()) }
        verify(exactly = 0) { users.save(any()) }
    }

    @Test
    @Story("Ошибка save пробрасывается наружу как есть")
    @Description("Лондон: даже при свободном имени отказ хранилища возвращается без маскировки.")
    fun `propagates repository failure from save`() {
        every { users.existsByUsername("neo") } returns false.right()
        every { encoder.encode(any()) } returns "hashed"
        every { users.save(any()) } returns Error.StorageFailure.left()

        useCase.execute("neo", "matrix").assertLeftType<Error.StorageFailure>()
    }
}
