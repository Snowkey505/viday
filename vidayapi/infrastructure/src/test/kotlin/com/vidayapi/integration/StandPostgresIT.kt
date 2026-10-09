package com.vidayapi.integration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate

/**
 * База для интеграционных тестов. Тесты подключаются к РЕАЛЬНОМУ инстансу PostgreSQL тестового стенда,
 * развёрнутому как ОТДЕЛЬНЫЙ инстанс хранилища, инициализированный последовательным запуском init-скриптов
 * Перед КАЖДЫМ тестом выполняется откат бизнес-данных к пустому состоянию
 * (TRUNCATE ... RESTART IDENTITY), поэтому прогоны повторяемы и не зависят друг от друга.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class StandPostgresIT {
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
        /** Откат состояния хранилища к состоянию до прогона тестов */
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

fun <T> Result<T>.valueOrThrow(): T = getOrThrow()

fun <T> Result<T>.valueOrNull(): T? = getOrNull()
