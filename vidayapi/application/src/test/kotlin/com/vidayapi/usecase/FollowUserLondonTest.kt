package com.vidayapi.usecase

import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.model.Error
import com.vidayapi.model.Role
import com.vidayapi.model.User
import com.vidayapi.port.UserRepository
import com.vidayapi.support.assertLeftType
import com.vidayapi.support.assertRight
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

@Tag("offline")
@Epic("London")
@Feature("Подписка на пользователя")
class FollowUserLondonTest {

    private val users = mockk<UserRepository>()
    private val useCase = FollowUserUseCase(users)
    private val bob = User(2, "bob", "hash", Role.USER, OffsetDateTime.parse("2026-01-01T00:00:00Z"))

    @Test
    @Story("findById создателя затем follow; self-follow не ходит в репозиторий")
    @Description("Лондон: проверяются вызовы UserRepository, состояние таблиц не существует.")
    fun `execute follows existing user and rejects self follow without io`() {
        every { users.findById(2) } returns bob.right()
        every { users.follow(1, 2) } returns Unit.right()

        useCase.execute(1, 2).assertRight()
        verify {
            users.findById(2)
            users.follow(1, 2)
        }

        useCase.execute(4, 4).assertLeftType<Error.CannotFollowSelf>()
        verify(exactly = 0) { users.findById(4) }
        verify(exactly = 0) { users.follow(4, 4) }
    }

    @Test
    @Story("Отсутствующий создатель — NotFound, follow не вызывается")
    @Description("Лондон: findById вернул null, подписка не пишется в репозиторий.")
    fun `returns not found when creator is missing`() {
        every { users.findById(999) } returns null.right()

        useCase.execute(1, 999).assertLeftType<Error.NotFound>()

        verify(exactly = 0) { users.follow(any(), any()) }
    }

    @Test
    @Story("Ошибка findById пробрасывается, follow не вызывается")
    @Description("Лондон: Left от порта прерывает цепочку через flatMap до подписки.")
    fun `propagates repository failure from findById`() {
        every { users.findById(2) } returns Error.StorageFailure.left()

        useCase.execute(1, 2).assertLeftType<Error.StorageFailure>()

        verify(exactly = 0) { users.follow(any(), any()) }
    }

    @Test
    @Story("Ошибка follow пробрасывается наружу как есть")
    @Description("Лондон: повторная подписка (в проде SQLState 23505) приходит как UserAlreadyFollowed.")
    fun `propagates repository failure from follow`() {
        every { users.findById(2) } returns bob.right()
        every { users.follow(1, 2) } returns Error.UserAlreadyFollowed.left()

        useCase.execute(1, 2).assertLeftType<Error.UserAlreadyFollowed>()
    }
}
