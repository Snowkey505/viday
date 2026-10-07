package com.vidayapi.service

import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.port.VideoMetadata
import com.vidayapi.port.VideoProcessor
import java.io.File
import java.io.InputStream

class FFmpegVideoProcessor : VideoProcessor {
    override fun analyze(inputStream: InputStream, fileSize: Long): Result<VideoMetadata> {
        val tempFile = File.createTempFile("video_upload_", ".tmp")
        try {
            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val command = listOf(
                "ffprobe",
                "-v", "error",
                "-select_streams", "v:0",
                "-count_packets",
                "-show_entries",
                "stream=width,height,duration,bit_rate",
                "-of", "csv=p=0",
                tempFile.absolutePath
            )

            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                return Error.InvalidInput.left()
            }

            val parts = output.split(",")
            if (parts.size < 3) {
                return Error.InvalidInput.left()
            }

            val width = parts[0].toIntOrNull() ?: 1920
            val height = parts[1].toIntOrNull() ?: 1080
            val duration = parts[2].toDoubleOrNull()?.toInt() ?: 0
            val bitrate = parts.getOrNull(3)?.toIntOrNull() ?: ((fileSize * 8) / maxOf(duration, 1)).toInt()

            return VideoMetadata(duration, width, height, bitrate).right()

        } catch (e: Exception) {
            return Error.StorageFailure.left()
        } finally {
            tempFile.delete()
        }
    }
}
