package com.vidayapi.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "business.video")
class VideoBusinessConfig {
    var maxDurationSeconds: Int = 3600
    var allowedFormats: List<String> = listOf("video/mp4", "video/webm")
    var maxFileSizeMb: Int = 500
}