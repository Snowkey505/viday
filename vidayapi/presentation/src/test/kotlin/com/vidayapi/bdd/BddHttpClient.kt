package com.vidayapi.bdd

import org.springframework.core.env.Environment
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Тонкий HTTP-клиент BDD-сценариев. Ходит по реальному HTTP в приложение,
 * поднятое @SpringBootTest(RANDOM_PORT).
 *
 * Порт берётся из Environment в момент запроса: local.server.port появляется
 * в окружении после старта встроенного сервера, а шаги выполняются уже после
 * этого события.
 */
class BddHttpClient(
    private val environment: Environment,
    private val mapper: ObjectMapper,
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun post(path: String, body: Any, token: String? = null): ApiResponse =
        exchange("POST", path, body, token)

    fun get(path: String, token: String? = null): ApiResponse =
        exchange("GET", path, null, token)

    private fun exchange(method: String, path: String, body: Any?, token: String?): ApiResponse {
        val builder = HttpRequest.newBuilder(URI.create("${baseUrl()}$path"))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        val publisher = if (body == null) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))
        }
        val response = client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        val raw = response.body().orEmpty()
        val json = if (raw.isBlank()) mapper.createObjectNode() else mapper.readTree(raw)
        return ApiResponse(response.statusCode(), json, raw)
    }

    private fun baseUrl(): String {
        val port = environment.getProperty("local.server.port")
            ?: error("local.server.port is not available — приложение ещё не запущено")
        return "http://localhost:$port"
    }

    data class ApiResponse(
        val status: Int,
        val body: JsonNode,
        val raw: String,
    )
}
