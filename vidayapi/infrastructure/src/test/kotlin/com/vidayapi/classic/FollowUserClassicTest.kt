package com.vidayapi.classic

import com.vidayapi.model.Error
import com.vidayapi.usecase.FollowUserUseCase
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
@Feature("Подписка на пользователя")
class FollowUserClassicTest : PerTestJdbcFixture {

    override val db = ClassicJdbc()
    private val register = RegisterUserUseCase(db.users, mockPasswordEncoder())
    private val useCase = FollowUserUseCase(db.users)

    @Test
    @Story("Подписка пишет user_follows; повтор - ошибка")
    @Description(
        "JdbcUserRepository.follow выполняет INSERT на подменённом Connection. " +
            "Повторный INSERT мапится из SQLState 23505 в UserAlreadyFollowed.",
    )
    fun `follow persists then rejects duplicate`() {
        val alice = register.execute("alice", "password123").valueOrThrow()
        val bob = register.execute("bob", "password123").valueOrThrow()

        useCase.execute(alice.id!!, bob.id!!).valueOrThrow()

        val follows = db.dataSource.catalog.rows("user_follows")
        assertThat(follows).hasSize(1)
        assertThat(follows.single()["following_user_id"]).isEqualTo(alice.id)
        assertThat(follows.single()["followed_user_id"]).isEqualTo(bob.id)

        assertThat(useCase.execute(alice.id!!, bob.id!!).errorOrThrow()).isEqualTo(Error.UserAlreadyFollowed)
        assertThat(db.dataSource.catalog.rows("user_follows")).hasSize(1)
    }

    @Test
    @Story("Подписка на несуществующего создателя - NotFound, ничего не пишется")
    @Description(
        "Классический стиль: JdbcUserRepository.findById возвращает null для отсутствующего id, " +
            "use case мапит это в Error.NotFound, INSERT в user_follows не выполняется.",
    )
    fun `follow returns not found for missing creator and writes nothing`() {
        assertThat(useCase.execute(1, 999).errorOrThrow()).isEqualTo(Error.NotFound)

        assertThat(db.dataSource.catalog.rows("user_follows")).isEmpty()
    }

    @Test
    @Story("Самоподписка, ошибка")
    @Description(
        "JdbcUserRepository.follow выполняет INSERT на подменённом Connection. " +
                "Повторный INSERT мапится из SQLState 23505 в UserAlreadyFollowed.",
    )
    fun `self follow`() {
        assertThat(useCase.execute(1, 1).errorOrThrow()).isEqualTo(Error.CannotFollowSelf)
        assertThat(db.dataSource.catalog.rows("user_follows")).isEmpty()
    }
}
