package com.vidayapi.integration

import com.vidayapi.model.Error
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@Epic("Integration")
@Feature("Доступ к данным: JdbcUserRepository на реальном PostgreSQL")
class UserFollowUnfollowIT : StandPostgresIT() {

    private val repos = IntegrationRepositories(jdbc)

    @Test
    @Story("подписка/отписка round-trip")
    @Description("INSERT/DELETE в viday.user_follows; повторная подписка -> UserAlreadyFollowed")
    fun `follow then unfollow round-trips through real table`() {
        // Arrange
        val a = repos.register.execute("a_it", "p").valueOrThrow()
        val b = repos.register.execute("b_it", "p").valueOrThrow()

        // Act
        repos.users.follow(a.id!!, b.id!!).valueOrThrow()

        // Assert: повторная подписка запрещена
        assertThat(repos.users.follow(a.id!!, b.id!!).exceptionOrNull())
            .isInstanceOf(Error.UserAlreadyFollowed::class.java)

        // Act: отписка
        repos.users.unfollow(a.id!!, b.id!!).valueOrThrow()

        // Assert: отписка от того, на кого не подписан -> NotFound
        assertThat(repos.users.unfollow(a.id!!, b.id!!).exceptionOrNull())
            .isInstanceOf(Error.NotFound::class.java)
    }
}
