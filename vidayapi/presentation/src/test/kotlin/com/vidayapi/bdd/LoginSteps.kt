package com.vidayapi.bdd

import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime

/** Шаги входа по паролю, лимита попыток и блокировки. */
class LoginSteps {

    @Autowired
    lateinit var context: ScenarioContext

    @Autowired
    lateinit var http: BddHttpClient

    @When("пользователь вводит верный пароль")
    fun loginWithCurrentPassword() {
        login(context.currentPassword)
    }

    @When("пользователь вводит неверный пароль")
    fun loginWithWrongPasswordOnce() {
        login(context.currentPassword + "-wrong")
    }

    @When("^пользователь вводит неверный пароль (\\d+) раз(?:а)? подряд$")
    fun loginWithWrongPasswordRepeatedly(times: Int) {
        repeat(times) { login(context.currentPassword + "-wrong") }
    }

    @When("пользователь вводит старый пароль")
    fun loginWithPreviousPassword() {
        val previous = context.previousPassword
            ?: error("Предыдущий пароль не зафиксирован")
        login(previous)
    }

    @Then("сервер требует второй фактор")
    fun twoFactorRequired() {
        val response = context.requireResponse()
        assertThat(response.status).isEqualTo(202)
        assertThat(response.body.get("twoFactorRequired")?.booleanValue()).isTrue()
        val challengeId = response.body.get("challengeId")?.stringValue()
        assertThat(challengeId).isNotBlank()
        context.challengeId = challengeId
    }

    @Then("сервер отправляет код на замаскированный email")
    fun codeSentToMaskedEmail() {
        val response = context.requireResponse()
        val masked = response.body.get("emailMasked")?.stringValue()
        assertThat(masked).isNotNull()
        assertThat(masked).contains("***", "@")
        assertThat(masked).isNotEqualTo(context.requireUser().email)
        assertThat(response.body.has("token")).isFalse()
    }

    @Then("сервер выдаёт JWT доступа")
    fun accessTokenIssued() {
        val response = context.requireResponse()
        assertThat(response.status).isEqualTo(200)
        val token = response.body.get("token")?.stringValue()
        assertThat(token).isNotBlank()
        context.accessToken = token
    }

    @Then("^сервер отклоняет пароль и сообщает, что осталось (\\d+) попыт(?:ка|ки|ок)$")
    fun passwordRejectedWithAttempts(attemptsRemaining: Int) {
        val response = context.requireResponse()
        assertThat(response.status).isEqualTo(401)
        assertThat(response.body.get("attemptsRemaining")?.intValue()).isEqualTo(attemptsRemaining)
    }

    @Then("сервер блокирует аккаунт на {int} минут")
    fun accountLocked(lockMinutes: Int) {
        val response = context.requireResponse()
        assertThat(response.status).isEqualTo(423)
        assertThat(response.body.get("error")?.stringValue()).isEqualTo("Account locked")
        val lockedUntil = response.body.get("lockedUntil")?.stringValue()
        assertThat(lockedUntil).isNotBlank()

        val until = OffsetDateTime.parse(lockedUntil)
        val now = OffsetDateTime.now()
        assertThat(until).isAfter(now.plusMinutes(lockMinutes.toLong() - 1))
        assertThat(until).isBefore(now.plusMinutes(lockMinutes.toLong() + 1))
    }

    @Then("сервер отклоняет старый пароль")
    fun oldPasswordRejected() {
        val response = context.requireResponse()
        assertThat(response.status).isEqualTo(401)
    }

    @Then("сервер сообщает, что требуется смена пароля")
    fun passwordChangeRequired() {
        val response = context.requireResponse()
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body.get("passwordChangeRequired")?.booleanValue()).isTrue()
        val token = response.body.get("passwordChangeToken")?.stringValue()
        assertThat(token).isNotBlank()
        context.passwordChangeToken = token
    }

    @Then("ограниченный токен не даёт доступа к API")
    fun limitedTokenCannotAccessApi() {
        val token = context.passwordChangeToken
            ?: error("Токен смены пароля не был выдан")
        // Реальный защищённый эндпоинт (GET /api/users/{id}). Токен с
        // purpose=password_change не аутентифицирует запрос, поэтому
        // SecurityFilterChain обязан вернуть 401/403, а не данные.
        val response = http.get("/api/users/1", token = token)
        assertThat(response.status).isIn(401, 403)
    }

    private fun login(password: String) {
        val user = context.requireUser()
        context.lastResponse = http.post(
            "/api/auth/login",
            mapOf("username" to user.username, "password" to password),
        )
    }
}
