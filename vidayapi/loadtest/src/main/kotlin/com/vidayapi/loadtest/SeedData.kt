package com.vidayapi.loadtest

import com.vidayapi.loadtest.http.LoadHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SeedData(
    val videoIds: List<Int>,
    val creatorIds: List<Int>,
    val playlistIds: List<Int>,
    val users: List<String>,
)

suspend fun loadSeed(config: LoadConfig, http: LoadHttpClient): SeedData {
    val response = http.request("GET", "/api/videos?page=0&size=100", "setup", "setup", record = false)
    check(response.status == 200) { "seed feed unavailable: HTTP ${response.status}" }
    val items = Json.parseToJsonElement(response.body).jsonObject["items"]?.jsonArray
        ?: error("seed feed has no items")
    val videos = items.map { it.jsonObject }
    val videoIds = videos.map { it.getValue("id").jsonPrimitive.int }
    val creatorIds = videos.map { it.getValue("ownerId").jsonPrimitive.int }.filter { it > 0 }.distinct()
    check(videoIds.isNotEmpty()) { "seed feed is empty" }
    check(creatorIds.isNotEmpty()) { "no creators found in seed feed" }
    return SeedData(
        videoIds = videoIds,
        creatorIds = creatorIds,
        playlistIds = (1..config.seedPlaylists).toList(),
        users = (1..config.userCount).map { "${config.userPrefix}$it" },
    )
}
