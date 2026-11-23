package com.vidayapi.config

data class VideoBusinessConfig(
    val maxDurationSeconds: Int = 3600,
    val allowedFormats: List<String> = listOf("video/mp4", "video/webm"),
    val maxFileSizeMb: Int = 500
)
