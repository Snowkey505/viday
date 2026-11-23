package com.vidayapi.bdd

/**
 * Состояние одного Cucumber-сценария.
 *
 * Сценарии выполняются последовательно (maxParallelForks = 1, параллельный
 * Cucumber не включён), поэтому один экземпляр переиспользуется, а хук
 * @Before очищает его перед каждым сценарием.
 *
 * Пароль хранится только в памяти процесса и никогда не попадает в feature-
 * файлы, Allure-отчёты или логи.
 */
class ScenarioContext {

    var technicalUser: TechnicalUser? = null
    var registrationStatus: Int = 0

    /** Текущий действующий пароль технического пользователя (только in-memory). */
    var currentPassword: String = ""

    /** Предыдущий пароль — для проверки, что после смены он отклоняется. */
    var previousPassword: String? = null

    var lastResponse: BddHttpClient.ApiResponse? = null
    var challengeId: String? = null
    var passwordChangeToken: String? = null
    var accessToken: String? = null

    fun reset() {
        technicalUser = null
        registrationStatus = 0
        currentPassword = ""
        previousPassword = null
        lastResponse = null
        challengeId = null
        passwordChangeToken = null
        accessToken = null
    }

    fun requireUser(): TechnicalUser =
        technicalUser ?: error("Технический пользователь не подготовлен")

    fun requireResponse(): BddHttpClient.ApiResponse =
        lastResponse ?: error("HTTP-запрос ещё не выполнялся")
}

/** Профиль технического пользователя. Пароль намеренно не хранится в toString. */
data class TechnicalUser(
    val username: String,
    val email: String,
)
