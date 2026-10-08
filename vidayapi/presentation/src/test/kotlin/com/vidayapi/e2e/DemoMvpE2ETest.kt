package com.vidayapi.e2e

import com.vidayapi.model.Result
import com.vidayapi.model.right
import com.vidayapi.port.VideoMetadata
import com.vidayapi.port.VideoProcessor
import io.qameta.allure.Allure
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Демонстрационный E2E-сценарий MVP vidayapi (ЛР2, Требование 3, Требование 13 — GUI не тестируется).
 *
 * Тест воспроизводит ровно тот сценарий, который показывается на защите MVP:
 *   регистрация креатора -> логин (JWT) -> активация канала ->
 *   загрузка видео -> создание публичного плейлиста -> добавление видео ->
 *   регистрация подписчика -> подписка -> проверка ленты и доступных плейлистов.
 *
 * Поднимает НАСТОЯЩЕЕ приложение (@SpringBootTest, RANDOM_PORT) поверх стенда из
 * docker-compose.test.yml: отдельный PostgreSQL + redis + minio. Все вызовы идут
 * через реальный HTTP-стек: SecurityFilterChain, JWT, RLS-фильтры, контроллеры,
 * use case'ы, JDBC-репозитории, PostgreSQL, MinIO.
 *
 * Параметры стенда берутся из системных свойств / окружения:
 * VIDAY_TEST_JDBC_URL, VIDAY_TEST_REDIS_HOST/PORT, VIDAY_TEST_MINIO_ENDPOINT.
 */
@Tag("e2e")
@Epic("E2E (ЛР2)")
@Feature("Демонстрационный сценарий MVP поверх реального HTTP")
@Story("MVP: регистрация -> вход -> канал -> видео -> плейлист -> подписка")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.main.allow-bean-definition-overriding=true"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MvpDemoScenarioE2ETest {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var mapper: ObjectMapper

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

    companion object {
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
            registry.add("spring.datasource.username") { prop("VIDAY_TEST_DB_USER", "viday_admin_user") }
            registry.add("spring.datasource.password") { prop("VIDAY_TEST_DB_PASSWORD", "admin_password_change_me") }
            registry.add("spring.data.redis.host") { prop("VIDAY_TEST_REDIS_HOST", "localhost") }
            registry.add("spring.data.redis.port") { prop("VIDAY_TEST_REDIS_PORT", "56380") }
            registry.add("minio.endpoint") { prop("VIDAY_TEST_MINIO_ENDPOINT", "http://localhost:59002") }
            registry.add("minio.public-endpoint") { prop("VIDAY_TEST_MINIO_PUBLIC_ENDPOINT", "http://127.0.0.1:59002") }
        }

        fun prop(name: String, default: String): String =
            System.getProperty(name) ?: System.getenv(name) ?: default
    }

    @TestConfiguration
    class E2eConfig {
        @Bean
        fun videoProcessor(): VideoProcessor = FixedMetadataVideoProcessor()
    }

    @BeforeEach
    fun resetBefore() {
        resetJdbc.execute(RESET_SQL)
    }

    @AfterEach
    fun cleanupStorage() {
        resetJdbc.execute(RESET_SQL)
    }

    @AfterAll
    fun closePool() {
        // resetDataSource — by lazy val, ::isInitialized здесь неприменим; закрываем безопасно
        runCatching { resetDataSource.close() }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
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

        Allure.parameter("password", "***")

        val creatorId = step("Регистрация креатора -> роль USER") {
            val resp = postJson("/api/auth/register", mapOf("username" to creator, "password" to password))
            assertThat(resp.statusCode()).isEqualTo(201)
            val id = resp.json().at("/id").longValue()
            assertThat(id).isPositive()
            id
        }

        val creatorToken = step("Логин креатора -> JWT") {
            login(creator, password)
        }

        step("Активация канала: USER -> CREATOR") {
            val activate = call("POST", "/api/users/me/channel/activate", token = creatorToken)
            assertThat(activate.statusCode()).isEqualTo(200)
            assertThat(activate.json().at("/user/role").stringValue()).isEqualTo("CREATOR")
        }

        val contentId = step("Загрузка видео (multipart)") {
            val video = uploadVideo(creatorToken, "MVP clip $stamp")
            val id = video.at("/id").longValue()
            assertThat(id).isPositive()
            assertThat(video.at("/durationSeconds").intValue()).isEqualTo(10)
            id
        }

        val playlistName = "Demo playlist $stamp"
        val playlistId = step("Создание публичного плейлиста") {
            val playlist = postJson(
                "/api/playlists",
                mapOf("name" to playlistName, "accessType" to "PUBLIC"),
                creatorToken,
            )
            assertThat(playlist.statusCode()).isEqualTo(201)
            val id = playlist.json().at("/id").longValue()
            assertThat(id).isPositive()
            id
        }

        step("Добавление контента в плейлист") {
            val add = postJson("/api/playlists/$playlistId/contents", mapOf("contentId" to contentId), creatorToken)
            assertThat(add.statusCode()).isEqualTo(200)
        }

        val followerToken = step("Регистрация подписчика, логин, подписка на креатора") {
            val reg = postJson("/api/auth/register", mapOf("username" to follower, "password" to password))
            assertThat(reg.statusCode()).isEqualTo(201)
            val token = login(follower, password)
            val follow = call("POST", "/api/users/$creatorId/follow", token = token)
            assertThat(follow.statusCode()).isEqualTo(200)
            token
        }

        step("Публичная лента содержит загруженное видео") {
            val feed = getJson("/api/videos?page=0&size=50")
            val items = feed.at("/items")
            assertThat(items.isArray).describedAs("items must be array").isTrue()

            val idsInFeed = items.mapNotNull { node ->
                node.at("/id").takeIf { !it.isMissingNode }?.longValue()
            }
            assertThat(idsInFeed).contains(contentId)
        }

        step("Подписчик видит публичный плейлист креатора") {
            val availableResp = call("GET", "/api/playlists/available", token = followerToken)
            assertThat(availableResp.statusCode())
                .describedAs("GET /api/playlists/available body: %s", availableResp.body())
                .isEqualTo(200)

            val available = availableResp.json()
            assertThat(available.isArray).describedAs("body: %s", availableResp.body()).isTrue()

            // ВАЖНО: у tools.jackson (Jackson 3) есть МЕТОД JsonNode.map(Function) —
            // Kotlin выбирает его вместо расширения Iterable.map, и лямбда получает
            // корневой узел, а не элементы массива. Итерируем элементы явно.
            val names = mutableListOf<String>()
            for (node in available) {
                names += node.at("/name").stringValue()
            }
            assertThat(names)
                .describedAs("available playlists: %s", availableResp.body())
                .contains(playlistName)
        }
    }


    /**
     * Обёртка над Allure.step: лямбда без явного SAM-типа даёт в Kotlin
     * перегрузочную неоднозначность между ThrowableRunnableVoid,
     * ThrowableRunnable<T> и ThrowableContextRunnable*-вариантами.
     */
    private fun <T> step(name: String, block: () -> T): T =
        Allure.step(name, Allure.ThrowableRunnable { block() })

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

    private data class MultipartPart(
        val name: String,
        val fileName: String?,
        val contentType: String,
        val data: ByteArray,
    )

    private fun uploadVideo(token: String, name: String): JsonNode {
        val boundary = "----viday-e2e-" + UUID.randomUUID()
        val fileBytes = ByteArray(256) { 0x41 }
        val previewBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        val body = buildMultipart(
            boundary,
            listOf(
                MultipartPart("file", "clip.mp4", "video/mp4", fileBytes),
                MultipartPart("preview", "preview.png", "image/png", previewBytes),
                MultipartPart("name", null, "text/plain", name.toByteArray(StandardCharsets.UTF_8)),
                MultipartPart("accessType", null, "text/plain", "PUBLIC".toByteArray(StandardCharsets.UTF_8)),
            ),
        )

        val resp = call(
            "POST",
            "/api/videos/upload",
            token = token,
            body = body,
            contentType = "multipart/form-data; boundary=$boundary",
        )
        assertThat(resp.statusCode()).describedAs("upload response: %s", resp.body()).isEqualTo(200)
        return resp.json()
    }

    private fun buildMultipart(boundary: String, parts: List<MultipartPart>): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray(StandardCharsets.UTF_8))

        for (p in parts) {
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"${p.name}\"")
            p.fileName?.let { write("; filename=\"$it\"") }
            write("\r\n")
            write("Content-Type: ${p.contentType}\r\n\r\n")
            out.write(p.data)
            write("\r\n")
        }
        write("--$boundary--\r\n")
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ HTTP

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
