package com.vidayapi.e2e

import com.vidayapi.model.Result
import com.vidayapi.model.right
import com.vidayapi.port.VideoMetadata
import com.vidayapi.port.VideoProcessor
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * E2E-тест ЛР2 (Задание 3, Требование 13 — GUI не тестируется, только HTTP).
 *
 * Поднимает НАСТОЯЩЕЕ приложение vidayapi (@SpringBootTest, RANDOM_PORT) поверх
 * окружения, развёрнутого в рамках этой лабораторной работы (Требование 6):
 * отдельный PostgreSQL-инстанс стенда (docker-compose.test.yml) + redis + minio.
 * Все обращения идут через реальный HTTP-стек: SecurityFilterChain, JWT,
 * RLS-фильтры, контроллеры, use case'ы, JDBC-репозитории, PostgreSQL.
 *
 * Сценарий = демонстрация MVP на защите:
 *   регистрация креатора -> логин (JWT) -> активация канала ->
 *   загрузка видео -> создание публичного плейлиста -> добавление видео ->
 *   регистрация подписчика -> подписка -> проверка ленты и видимости плейлиста.
 *
 * Параметры стенда берутся из системных свойств / окружения
 * (VIDAY_TEST_JDBC_URL, VIDAY_TEST_REDIS_HOST/PORT, VIDAY_TEST_MINIO_ENDPOINT).
 */
@Tag("e2e")
@Epic("E2E (ЛР2)")
@Feature("Демонстрационный сценарий MVP поверх реального HTTP")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.main.allow-bean-definition-overriding=true"],
)
class DemoMvpE2ETest {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    /**
     * Отдельное подключение к стенду для отката хранилища: используется суперпользователь
     * viday_admin_user (владелец таблиц и последовательностей), поэтому TRUNCATE ...
     * RESTART IDENTITY выполняется независимо от того, каким пользователем приложение
     * подключилось к БД. RLS на это подключение не влияет (транзакция отката).
     */
    private val resetDataSource: HikariDataSource by lazy {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = prop("VIDAY_TEST_JDBC_URL", "jdbc:postgresql://localhost:55432/viday")
                username = prop("VIDAY_TEST_DB_USER", "viday_admin_user")
                password = prop("VIDAY_TEST_DB_PASSWORD", "admin_password_change_me")
                maximumPoolSize = 2
            }
        )
    }
    private val resetJdbc: JdbcTemplate by lazy { JdbcTemplate(resetDataSource) }

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val mapper = ObjectMapper()

    companion object {
        /** Откат тестового хранилища к состоянию до прогона (Требования 10/14). */
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

        @JvmStatic
        @DynamicPropertySource
        fun standProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { prop("VIDAY_TEST_JDBC_URL", "jdbc:postgresql://localhost:55432/viday") }
            // Суперпользователь стенда: нужен для отката (TRUNCATE RESTART IDENTITY) и
            // не мешает RLS — фильтры приложения делают SET ROLE на каждое соединение.
            registry.add("spring.datasource.username") { prop("VIDAY_TEST_DB_USER", "viday_admin_user") }
            registry.add("spring.datasource.password") { prop("VIDAY_TEST_DB_PASSWORD", "admin_password_change_me") }
            registry.add("spring.data.redis.host") { prop("VIDAY_TEST_REDIS_HOST", "localhost") }
            registry.add("spring.data.redis.port") { prop("VIDAY_TEST_REDIS_PORT", "56380") }
            registry.add("minio.endpoint") { prop("VIDAY_TEST_MINIO_ENDPOINT", "http://localhost:59002") }
            registry.add("minio.public-endpoint") { prop("VIDAY_TEST_MINIO_PUBLIC_ENDPOINT", "http://127.0.0.1:59002") }
        }

        private fun prop(name: String, default: String): String =
            System.getProperty(name) ?: System.getenv(name) ?: default
    }

    /** Детерминированный анализатор метаданных вместо внешнего бинарника ffprobe. */
    @TestConfiguration
    class E2eConfig {
        @Bean
        fun videoProcessor(): VideoProcessor = FixedMetadataVideoProcessor()
    }

    @BeforeEach
    fun resetBefore() {
        // Чистое состояние перед прогоном (Требования 10/14): тест стартует с
        // пустых бизнес-таблиц независимо от предыдущих запусков.
        resetJdbc.execute(RESET_SQL)
    }

    @AfterEach
    fun cleanupStorage() {
        // Целостность тестового хранилища после прогона (Требования 10/14):
        // принудительный откат к состоянию до запуска тестов.
        resetJdbc.execute(RESET_SQL)
    }

    @Test
    @Story("MVP: регистрация -> вход -> канал -> видео -> плейлист -> подписка")
    @Description(
        "Полный демонстрационный сценарий поверх реального HTTP и реального PostgreSQL стенда: " +
            "JWT-авторизация, RLS-фильтры, загрузка multipart, бизнес-логика и проверка " +
            "публичной ленты/доступных плейлистов для подписчика.",
    )
    fun `mvp demo scenario works end-to-end over real HTTP against the test stand`() {
        val stamp = System.currentTimeMillis()
        val creator = "creator_e2e_$stamp"
        val follower = "follower_e2e_$stamp"
        val password = "Secret$stamp!"

        // 1. Регистрация креатора -> роль USER
        val registerResp = postJson("/api/auth/register", mapOf("username" to creator, "password" to password))
        assertThat(registerResp.statusCode()).isEqualTo(201)
        val creatorId = registerResp.json().at("/id").longValue()
        assertThat(creatorId).isPositive()

        // 2. Логин -> JWT
        val creatorToken = login(creator, password)

        // 3. Активация канала: USER -> CREATOR
        val activate = call("POST", "/api/users/me/channel/activate", token = creatorToken)
        assertThat(activate.statusCode()).isEqualTo(200)
        assertThat(activate.json().at("/user/role").stringValue()).isEqualTo("CREATOR")

        // 4. Загрузка видео (multipart) — пишется в реальный PostgreSQL и MinIO стенда
        val video = uploadVideo(creatorToken, "MVP clip $stamp")
        val contentId = video.at("/id").longValue()
        assertThat(contentId).isPositive()
        assertThat(video.at("/durationSeconds").intValue()).isEqualTo(10)

        // 5. Создание публичного плейлиста
        val playlistName = "Demo playlist $stamp"
        val playlist = postJson("/api/playlists", mapOf("name" to playlistName, "accessType" to "PUBLIC"), creatorToken)
        assertThat(playlist.statusCode()).isEqualTo(201)
        val playlistId = playlist.json().at("/id").longValue()
        assertThat(playlistId).isPositive()

        // 6. Добавление контента в плейлист
        val add = postJson("/api/playlists/$playlistId/contents", mapOf("contentId" to contentId), creatorToken)
        assertThat(add.statusCode()).isEqualTo(200)

        // 7. Подписчик: регистрация + логин + подписка на креатора
        assertThat(postJson("/api/auth/register", mapOf("username" to follower, "password" to password)).statusCode())
            .isEqualTo(201)
        val followerToken = login(follower, password)
        val follow = call("POST", "/api/users/$creatorId/follow", token = followerToken)
        assertThat(follow.statusCode()).isEqualTo(200)

        // 8. Проверка: публичная лента содержит загруженное видео
        val feed = getJson("/api/videos?page=0&size=50")
        val idsInFeed = feed.at("/items").mapNotNull { node -> node.at("/id").takeIf { !it.isMissingNode() }?.longValue() }
        assertThat(idsInFeed).contains(contentId)

        // 9. Проверка: подписчик видит публичный плейлист креатора
        val availableResp = call("GET", "/api/playlists/available", token = followerToken)
        assertThat(availableResp.statusCode())
            .describedAs("GET /api/playlists/available body: %s", availableResp.body())
            .isEqualTo(200)
        val available = availableResp.json()
        assertThat(available.isArray).describedAs("body: %s", availableResp.body()).isTrue()
        assertThat(available.size()).isEqualTo(1)
        assertThat(availableResp.body()).contains("\"name\":\"" + playlistName + "\"")

        // 10. Инвариант безопасности: без токена доступ к плейлистам запрещён
        //     (Spring Security для анонимов отдаёт 403 Forbidden по умолчанию)
        val anon = call("GET", "/api/playlists/available")
        assertThat(anon.statusCode()).isIn(401, 403)
    }

    // ------------------------------------------------------------------ helpers

    private fun login(username: String, password: String): String {
        val resp = postJson("/api/auth/login", mapOf("username" to username, "password" to password))
        assertThat(resp.statusCode()).isEqualTo(200)
        val token = resp.json().at("/token").stringValue()
        assertThat(token).isNotBlank()
        return token
    }

    private fun postJson(path: String, body: Map<String, Any>, token: String? = null): HttpResponse<String> =
        call("POST", path, token = token, body = mapper.writeValueAsBytes(body), contentType = "application/json")

    private fun getJson(path: String, token: String? = null): JsonNode =
        call("GET", path, token = token).json()

    private fun HttpResponse<String>.json(): JsonNode =
        if (body().isBlank()) mapper.createObjectNode() else mapper.readTree(body())

    private fun uploadVideo(token: String, name: String): JsonNode {
        val boundary = "----viday-e2e-" + UUID.randomUUID()
        val fileBytes = ByteArray(256) { 0x41 }
        val previewBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        val body = StringBuilder()
        fun part(name: String, fileName: String?, contentType: String, data: ByteArray?) {
            body.append("--").append(boundary).append("\r\n")
            body.append("Content-Disposition: form-data; name=\"").append(name).append("\"")
            if (fileName != null) body.append("; filename=\"").append(fileName).append("\"")
            body.append("\r\n")
            body.append("Content-Type: ").append(contentType).append("\r\n\r\n")
            if (data != null) {
                body.append(String(data, StandardCharsets.ISO_8859_1)).append("\r\n")
            } else {
                body.append("\r\n")
            }
        }
        part("file", "clip.mp4", "video/mp4", fileBytes)
        part("preview", "preview.png", "image/jpeg", previewBytes)
        part("name", null, "text/plain", name.toByteArray())
        part("accessType", null, "text/plain", "PUBLIC".toByteArray())
        body.append("--").append(boundary).append("--\r\n")

        val resp = call(
            "POST",
            "/api/videos/upload",
            token = token,
            body = body.toString().toByteArray(StandardCharsets.ISO_8859_1),
            contentType = "multipart/form-data; boundary=$boundary",
        )
        assertThat(resp.statusCode()).describedAs("upload response: %s", resp.body()).isEqualTo(200)
        return resp.json()
    }

    private fun call(
        method: String,
        path: String,
        token: String? = null,
        body: ByteArray? = null,
        contentType: String = "application/json",
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", contentType)
        token?.let { builder.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> builder.GET()
            "DELETE" -> builder.DELETE()
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofByteArray(body ?: ByteArray(0)))
            "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofByteArray(body ?: ByteArray(0)))
            else -> error("unsupported method $method")
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}

/** Мини-реализация [VideoProcessor] с фиксированными метаданными (без внешнего ffprobe). */
class FixedMetadataVideoProcessor : VideoProcessor {
    override fun analyze(inputStream: InputStream, fileSize: Long): Result<VideoMetadata> =
        VideoMetadata(durationSeconds = 10, width = 1280, height = 720, bitrate = 2500).right()
}