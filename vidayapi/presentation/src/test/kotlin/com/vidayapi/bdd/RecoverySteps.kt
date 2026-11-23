package com.vidayapi.bdd

import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** Шаги восстановления доступа после блокировки. */
class RecoverySteps {

    @Autowired
    lateinit var context: ScenarioContext

    @Autowired
    lateinit var http: BddHttpClient

    @Autowired
    lateinit var support: BddSupport

    @When("пользователь запрашивает восстановление доступа")
    fun requestRecovery() {
        val user = context.requireUser()
        context.lastResponse = http.post(
            "/api/auth/recovery/request",
            mapOf("username" to user.username),
        )
    }

    @When("запрашивается восстановление для неизвестного пользователя")
    fun requestRecoveryForUnknownUser() {
        val unknown = "lr5_missing_" + UUID.randomUUID().toString().replace("-", "").take(10)
        context.lastResponse = http.post(
            "/api/auth/recovery/request",
            mapOf("username" to unknown),
        )
    }

    @When("пользователь подтверждает восстановление с новым паролем")
    fun confirmRecoveryWithNewPassword() {
        val user = context.requireUser()
        val code = support.readRecoveryCode(user.email)
        val newPassword = Passwords.generate()

        val response = http.post(
            "/api/auth/recovery/confirm",
            mapOf(
                "username" to user.username,
                "code" to code,
                "newPassword" to newPassword,
            ),
        )
        context.lastResponse = response

        if (response.status == 200) {
            context.previousPassword = context.currentPassword
            context.currentPassword = newPassword
        }
    }

    @Then("сервер подтверждает восстановление доступа")
    fun recoveryConfirmed() {
        val response = context.requireResponse()
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body.get("message")?.stringValue()).isEqualTo("Password recovered")
    }
}
