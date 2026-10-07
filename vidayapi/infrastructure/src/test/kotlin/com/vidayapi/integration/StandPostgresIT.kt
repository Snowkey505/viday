package com.vidayapi.integration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate

/**
 * База для интеграционных тестов ЛР2.
 *
 * Тесты подключаются к РЕАЛЬНОМУ инстансу PostgreSQL тестового стенда
 * (`docker-compose.test.yml`, порт по умолчанию 55432), развёрнутому в рамках
 * этой лабораторной работы (Требование 6) как ОТДЕЛЬНЫЙ инстанс хранилища
 * (Требование 3), инициализированный последовательным запуском init-скриптов
 * 01..05 (Требование 4).
 *
 * Перед КАЖДЫМ тестом выполняется откат бизнес-данных к пустому состоянию
 * (TRUNCATE ... RESTART IDENTITY), поэтому прогоны повторяемы (Требование 14)
 * и не зависят друг от друга.
 *
 * Параметры подключения берутся из системных свойств / окружения:
 *   VIDAY_TEST_JDBC_URL, VIDAY_TEST_DB_USER, VIDAY_TEST_DB_PASSWORD.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class StandPostgresIT {

    // Подключаемся как viday_admin_user (суперпользователь стенда): он владеет
    // последовательностями, поэтому TRUNCATE ... RESTART IDENTITY и весь откат
    // состояния (Требования 4/10/14) выполняются без правовых конфликтов.
    // RLS в E2E-контуре продолжает работать: приложение внутри теста делает
    // SET ROLE viday_user/viday_creator/... на каждое соединение.
    protected val dataSource: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = prop("VIDAY_TEST_JDBC_URL", "jdbc:postgresql://localhost:55432/viday")
            username = prop("VIDAY_TEST_DB_USER", "viday_admin_user")
            password = prop("VIDAY_TEST_DB_PASSWORD", "admin_password_change_me")
            maximumPoolSize = 4
        }
    )

    protected val jdbc: JdbcTemplate = JdbcTemplate(dataSource)

    @BeforeEach
    fun resetStorage() {
        jdbc.execute(RESET_SQL)
    }

    @AfterAll
    fun closePool() {
        dataSource.close()
    }

    companion object {
        /** Откат состояния хранилища к состоянию до прогона тестов (Требования 4, 10, 14). */
        val RESET_SQL = """
            TRUNCATE TABLE
                viday.user_follows,
                viday.content_to_playlist,
                viday.user_to_playlist,
                viday.media_variant,
                viday.video,
                viday.stream,
                viday.content,
                viday.playlist,
                viday."user",
                viday.content_view_stats
            RESTART IDENTITY CASCADE;
        """.trimIndent()

        private fun prop(name: String, default: String): String =
            System.getProperty(name) ?: System.getenv(name) ?: default
    }
}

/** Достать успешное значение или бросить исключение (kotlin.Result). */
fun <T> Result<T>.valueOrThrow(): T = getOrThrow()

/** Достать успешное значение или null при ошибке (kotlin.Result). */
fun <T> Result<T>.valueOrNull(): T? = getOrNull()