package com.vidayapi.bdd

import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired

/** Шаги проверки одноразового кода из письма. */
class TwoFactorSteps {

    @Autowired
    lateinit var context: ScenarioContext

    @Autowired
    lateinit var http: BddHttpClient

    @Autowired
    lateinit var support: BddSupport

    @When("пользователь вводит код из последнего письма")
    fun enterCodeFromLastEmail() {
        verify(validCode())
    }

    @When("пользователь вводит неверный код из письма")
    fun enterWrongCodeOnce() {
        verifyWithWrongCode()
    }

    @When("^пользователь вводит неверный код из письма (\\d+) раз(?:а)? подряд$")
    fun enterWrongCodeRepeatedly(times: Int) {
        repeat(times) { verifyWithWrongCode() }
    }

    @Then("^сервер отклоняет код и сообщает, что осталось (\\d+) попыт(?:ка|ки|ок)$")
    fun codeRejectedWithAttempts(attemptsRemaining: Int) {
        val response = context.requireResponse()
        assertThat(response.status).isEqualTo(401)
        assertThat(response.body.get("attemptsRemaining")?.intValue()).isEqualTo(attemptsRemaining)
    }

    @Then("сервер отклоняет код, так как лимит попыток исчерпан")
    fun codeRejectedBecauseAttemptsExhausted() {
        val response = context.requireResponse()
        assertThat(response.status).isEqualTo(400)
        assertThat(response.body.get("error")?.stringValue())
            .containsIgnoringCase("expired")
    }

    private fun verifyWithWrongCode() {
        verify(Passwords.wrongCode(validCode()))
    }

    private fun verify(code: String) {
        val challengeId = context.challengeId
            ?: error("Challenge второго фактора не создан")
        val response = http.post(
            "/api/auth/2fa/verify",
            mapOf("challengeId" to challengeId, "code" to code),
        )
        context.lastResponse = response
        response.body.get("token")?.stringValue()?.let { context.accessToken = it }
        response.body.get("passwordChangeToken")?.stringValue()?.let { context.passwordChangeToken = it }
    }

    private fun validCode(): String {
        val challengeId = context.challengeId
            ?: error("Challenge второго фактора не создан")
        return support.readChallengeCode(challengeId)
    }
}
