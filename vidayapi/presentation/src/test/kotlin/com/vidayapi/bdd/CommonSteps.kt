package com.vidayapi.bdd

import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired

/** Общие шаги подготовки данных и проверки технического пользователя. */
class CommonSteps {

    @Autowired
    lateinit var context: ScenarioContext

    @Autowired
    lateinit var support: BddSupport

    @Given("для сценария подготовлен технический пользователь")
    fun technicalUserPrepared() {
        assertThat(context.technicalUser).isNotNull()
        assertThat(context.registrationStatus).isEqualTo(201)
    }

    @Then("сервер подтвердил регистрацию технического пользователя")
    fun registrationConfirmed() {
        assertThat(context.registrationStatus).isEqualTo(201)
    }

    @Then("технический пользователь существует в базе с ролью {string}")
    fun technicalUserExistsWithRole(role: String) {
        val username = context.requireUser().username
        assertThat(support.userExists(username)).isTrue()
        assertThat(support.roleOf(username)).isEqualTo(role)
    }

    @Then("сервер принимает запрос на восстановление")
    fun recoveryRequestAccepted() {
        val response = context.requireResponse()
        assertThat(response.status).isEqualTo(202)
        assertThat(response.body.get("message")?.stringValue()).isNotBlank()
    }
}
