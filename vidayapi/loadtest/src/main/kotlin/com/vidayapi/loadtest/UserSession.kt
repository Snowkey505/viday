package com.vidayapi.loadtest

import com.vidayapi.loadtest.http.LoadHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class UserSession(
    private val config: LoadConfig,
    private val seed: SeedData,
    private val http: LoadHttpClient,
) {
    suspend fun execute(workerId: Int, iteration: Long, phase: String) {
        if (config.workload == "public") {
            executePublic(workerId, iteration, phase)
            return
        }
        val sessionSeed = workerId * 1_000_003L + iteration
        val username = seed.users.pick(sessionSeed)
        val videoId = seed.videoIds.pick(sessionSeed)
        val playlistId = seed.playlistIds.pick(sessionSeed shr 3)
        val creatorId = seed.creatorIds.pick(sessionSeed shr 5)
        val errorCount = (config.errorRate * 10).toInt()
        val actions = when (10 - errorCount) {
            10 -> listOf("login", "feed", "video", "playlist", "available", "create", "add", "delete", "follow", "unfollow")
            9 -> listOf("login", "feed", "video", "available", "create", "add", "delete", "follow", "unfollow")
            else -> listOf("login", "feed", "video", "available", "playlist", "create", "add", "delete")
        }

        var token: String? = null
        var newPlaylistId: Int? = null
        for (action in actions) {
            when (action) {
                "login" -> {
                    val response = http.request(
                        "POST", "/api/auth/login", "login", phase,
                        body = json("username" to username, "password" to config.password),
                    )
                    if (response.status == 200) token = response.jsonString("token") else break
                }
                "feed" -> http.request("GET", "/api/videos?page=0&size=20", "feed", phase)
                "video" -> http.request("GET", "/api/videos/$videoId", "video_detail", phase)
                "available" -> token?.let {
                    http.request("GET", "/api/playlists/available", "available_playlists", phase, it)
                }
                "playlist" -> token?.let {
                    http.request("GET", "/api/playlists/$playlistId", "playlist_detail", phase, it)
                }
                "create" -> token?.let {
                    val name = "lt-${config.runId}-$workerId-$iteration-${System.currentTimeMillis()}"
                    val response = http.request(
                        "POST", "/api/playlists", "create_playlist", phase, it,
                        json("name" to name, "accessType" to "PUBLIC"),
                    )
                    if (response.status == 201) newPlaylistId = response.jsonInt("id")
                }
                "add" -> if (token != null && newPlaylistId != null) {
                    http.request(
                        "POST", "/api/playlists/$newPlaylistId/contents", "add_content", phase, token,
                        json("contentId" to videoId, "position" to 1),
                    )
                }
                "delete" -> if (token != null && newPlaylistId != null) {
                    http.request("DELETE", "/api/playlists/$newPlaylistId", "delete_playlist", phase, token)
                    newPlaylistId = null
                }
                "follow" -> token?.let {
                    http.request("POST", "/api/users/$creatorId/follow", "follow", phase, it)
                }
                "unfollow" -> token?.let {
                    http.request("DELETE", "/api/users/$creatorId/follow", "unfollow", phase, it)
                }
            }
            if (action != "login" && action != "feed" && action != "video" && token == null) break
        }

        repeat(errorCount) { index ->
            when (((sessionSeed + index) % ERROR_TYPES.size).toInt()) {
                0 -> http.request(
                    "POST", "/api/auth/login", "error_invalid_login", phase,
                    body = json("username" to username, "password" to "definitely-wrong-password"),
                    injectedError = true,
                )
                1 -> http.request(
                    "POST", "/api/playlists", "error_unauthorized_playlist", phase,
                    body = json("name" to "unauthorized-$sessionSeed", "accessType" to "PUBLIC"),
                    injectedError = true,
                )
                2 -> http.request(
                    "POST", "/api/playlists", "error_invalid_playlist", phase, token,
                    json("name" to "", "accessType" to "PUBLIC"), true,
                )
                else -> http.request(
                    "GET", "/api/users/99999999", "error_not_found_user", phase, token,
                    injectedError = true,
                )
            }
        }
    }

    private suspend fun executePublic(workerId: Int, iteration: Long, phase: String) {
        val sessionSeed = workerId * 1_000_003L + iteration
        val videoId = seed.videoIds.pick(sessionSeed)
        http.request("GET", "/api/videos?page=0&size=20", "feed", phase)
        http.request("GET", "/api/videos/$videoId", "video_detail", phase)
        http.request("GET", "/api/streams/live", "live_streams", phase)
    }

    private fun json(vararg values: Pair<String, Any>): String = buildJsonObject {
        values.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is Int -> put(key, value)
                else -> error("unsupported JSON value")
            }
        }
    }.toString()

    private fun com.vidayapi.loadtest.http.LoadResponse.jsonString(key: String): String? = try {
        Json.parseToJsonElement(body).jsonObject[key]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    private fun com.vidayapi.loadtest.http.LoadResponse.jsonInt(key: String): Int? = try {
        Json.parseToJsonElement(body).jsonObject[key]?.jsonPrimitive?.intOrNull
    } catch (_: Exception) {
        null
    }

    private fun <T> List<T>.pick(index: Long): T = this[Math.floorMod(index, size.toLong()).toInt()]

    companion object {
        private val ERROR_TYPES = listOf("invalid_login", "unauthorized_playlist", "invalid_playlist", "not_found_user")
    }
}
