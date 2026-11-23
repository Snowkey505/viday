package com.vidayapi.bdd

import io.cucumber.spring.CucumberContextConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Spring-контекст для всех Cucumber-сценариев ЛР5.
 *
 * Приложение поднимается один раз на весь BDD-прогон (RANDOM_PORT) поверх
 * реального тестового стенда docker-compose.test.yml. Все шаги ходят через
 * настоящий HTTP-стек: SecurityFilterChain, JWT, RLS, контроллеры и PostgreSQL.
 *
 * Пароль технического пользователя и JWT-ключ не задаются здесь: они приходят
 * из окружения (GitHub Actions secrets) либо генерируются эфемерно.
 */
@CucumberContextConfiguration
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.main.allow-bean-definition-overriding=true"],
)
@Import(BddTestConfig::class)
class CucumberSpringConfiguration {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun standProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                prop("VIDAY_TEST_JDBC_URL", "jdbc:postgresql://localhost:55432/viday")
            }
            registry.add("spring.datasource.username") {
                prop("VIDAY_TEST_DB_USER", "viday_admin_user")
            }
            registry.add("spring.datasource.password") {
                prop("VIDAY_TEST_DB_PASSWORD", "admin_password_change_me")
            }
            registry.add("spring.data.redis.host") { prop("VIDAY_TEST_REDIS_HOST", "localhost") }
            registry.add("spring.data.redis.port") { prop("VIDAY_TEST_REDIS_PORT", "56380") }
            registry.add("minio.endpoint") {
                prop("VIDAY_TEST_MINIO_ENDPOINT", "http://localhost:59002")
            }
            registry.add("minio.public-endpoint") {
                prop("VIDAY_TEST_MINIO_PUBLIC_ENDPOINT", "http://127.0.0.1:59002")
            }
            // JWT_SECRET: секрет CI (GitHub Actions / GitLab CI / Vault).
            // Если не задан — JwtSecretProvider сгенерирует эфемерный ключ,
            // поэтому тест остаётся самодостаточным локально.
            prop("JWT_SECRET", "")
                .takeIf { it.isNotBlank() }
                ?.let { secret -> registry.add("jwt.secret") { secret } }
        }

        private fun prop(name: String, default: String): String =
            System.getProperty(name) ?: System.getenv(name) ?: default
    }
}
