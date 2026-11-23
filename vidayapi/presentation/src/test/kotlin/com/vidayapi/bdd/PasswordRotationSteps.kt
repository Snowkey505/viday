package com.vidayapi.bdd

import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired

/** Шаги плановой смены пароля (ротации). */
class PasswordRotationSteps {

    @Autowired
    lateinit var context: ScenarioContext

    @Autowired
    lateinit var http: BddHttpClient

    @Autowired
    lateinit var support: BddSupport

    // Регулярное выражение, а не Cucumber expression: в русском feature-файле
    // после числа стоит форма слова («91 день», «2 дня», «5 дней»), и жёсткий
    // литерал «дней» не совпал бы с «91 день».
    @Given("^пароль технического пользователя не менялся (\\d+) (?:день|дня|дней)$")
    fun passwordHasNotBeenChangedForDays(days: Int) {
        support.expirePassword(context.requireUser().username, days)
    }

    @When("пользователь меняет пароль по выданному токену на новый")
    fun changePasswordWithLimitedToken() {
        val token = context.passwordChangeToken
            ?: error("Токен смены пароля не был выдан")
        val newPassword = Passwords.generate()

        val response = http.post(
            "/api/auth/password/change",
            mapOf(
                "passwordChangeToken" to token,
                "newPassword" to newPassword,
            ),
        )
        context.lastResponse = response

        if (response.status == 200) {
            context.previousPassword = context.currentPassword
            context.currentPassword = newPassword
        }
    }

    @Then("сервер подтверждает смену пароля")
    fun passwordChangeConfirmed() {
        val response = context.requireResponse()
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body.get("message")?.stringValue()).isEqualTo("Password changed")
    }
}
