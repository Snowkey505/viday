package com.vidayapi.bdd

import io.cucumber.java.After
import io.cucumber.java.Before
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Подготовка тестовых данных (Требование 4 ЛР5).
 *
 * Перед каждым сценарием создаётся УНИКАЛЬНЫЙ технический пользователь через
 * реальный API регистрации, после сценария он удаляется из БД вместе со всеми
 * каскадными auth-записями. Это исключает влияние сценариев друг на друга и
 * не требует заранее заведённых учётных записей.
 *
 * Пароль:
 *  * в CI берётся из секрета VIDAY_LR5_TECH_PASSWORD
 *    (GitHub Actions secret; в GitLab — protected CI variable, либо Vault);
 *  * локально, если секрет не задан, генерируется эфемерно;
 *  * НИКОГДА не пишется в feature-файлы, документацию, логи или Allure.
 */
class TechnicalUserHooks {

    @Autowired
    lateinit var context: ScenarioContext

    @Autowired
    lateinit var http: BddHttpClient

    @Autowired
    lateinit var support: BddSupport

    private val logger = LoggerFactory.getLogger(TechnicalUserHooks::class.java)

    @Before(order = 0)
    fun createTechnicalUser() {
        context.reset()

        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val username = "lr5_tech_$suffix"
        val email = "$username@lr5.viday.test"
        val password = resolvePassword()

        val response = http.post(
            "/api/auth/register",
            mapOf(
                "username" to username,
                "password" to password,
                "email" to email,
            ),
        )
        context.registrationStatus = response.status
        check(response.status == 201) {
            "Не удалось создать технического пользователя: HTTP ${response.status}. " +
                "Если тестовый стенд создан до ЛР5, в нём нет таблиц user_auth/" +
                "auth_challenge: пересоздайте стенд командой `make stand-reset`."
        }

        context.technicalUser = TechnicalUser(username = username, email = email)
        context.currentPassword = password
        logger.info("Technical user provisioned: username={}, email={}", username, email)
    }

    @After(order = 100)
    fun deleteTechnicalUser() {
        val user = context.technicalUser ?: return
        try {
            support.deleteUserByUsername(user.username)
            logger.info("Technical user deleted: username={}", user.username)
        } catch (e: Exception) {
            // Не маскируем падение сценария, но и не оставляем пользователя
            // незамеченным: в отчёте будет предупреждение без секретов.
            logger.warn("Failed to delete technical user: username={}", user.username, e)
        }
    }

    private fun resolvePassword(): String {
        // В CI пароль приходит из секрета (GitHub Actions secret / GitLab
        // protected variable / Vault). System property проверяем первым:
        // Gradle testBdd явно пробрасывает секрет в test-JVM.
        val fromSecret = (System.getProperty("VIDAY_LR5_TECH_PASSWORD")
            ?: System.getenv("VIDAY_LR5_TECH_PASSWORD"))
            ?.trim()
        return if (!fromSecret.isNullOrEmpty()) fromSecret else Passwords.generate()
    }
}
