package com.vidayapi.port

import java.io.InputStream
import com.vidayapi.model.Result

interface VideoProcessor {
    fun analyze(inputStream: InputStream, fileSize: Long): Result<VideoMetadata>
}

data class VideoMetadata(
    val durationSeconds: Int,
    val width: Int,
    val height: Int,
    val bitrate: Int
)
