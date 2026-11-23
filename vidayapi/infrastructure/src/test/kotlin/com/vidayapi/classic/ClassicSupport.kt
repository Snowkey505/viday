package com.vidayapi.classic

import com.vidayapi.config.VideoBusinessConfig
import com.vidayapi.jdbc.InMemoryPostgresDataSource
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.right
import com.vidayapi.port.CachePort
import com.vidayapi.port.FileStoragePort
import com.vidayapi.port.PasswordEncoder
import com.vidayapi.port.VideoMetadata
import com.vidayapi.port.VideoProcessor
import com.vidayapi.repository.JdbcContentRepository
import com.vidayapi.repository.JdbcPlaylistRepository
import com.vidayapi.repository.JdbcUserRepository
import io.mockk.every
import io.mockk.mockk
import org.slf4j.helpers.NOPLogger
import org.springframework.jdbc.core.JdbcTemplate
import java.io.InputStream

class ClassicJdbc {
    val dataSource = InMemoryPostgresDataSource()
    val jdbc = JdbcTemplate(dataSource)
    val users = JdbcUserRepository(jdbc)
    val playlists = JdbcPlaylistRepository(jdbc, NOPLogger.NOP_LOGGER)
    val content = JdbcContentRepository(jdbc)

    fun reset() {
        dataSource.catalog.clearBusinessData()
    }
}

class RecordingFileStorage : FileStoragePort {
    val uploaded = mutableListOf<String>()

    override fun uploadFile(
        bucketName: String,
        objectName: String,
        inputStream: InputStream,
        contentType: String,
        size: Long,
    ): Result<String> {
        val path = "$bucketName/$objectName"
        uploaded += path
        return path.right()
    }

    override fun getFileUrl(bucketName: String, objectName: String): Result<String> =
        "https://cdn.local/$bucketName/$objectName".right()

    override fun deleteFile(bucketName: String, objectName: String): Result<Unit> = Unit.right()
}

class FixedVideoProcessor(
    private val metadata: VideoMetadata = VideoMetadata(12, 1920, 1080, 4000),
) : VideoProcessor {
    override fun analyze(inputStream: InputStream, fileSize: Long): Result<VideoMetadata> = metadata.right()
}

class MapCache : CachePort {
    val deletedPrefixes = mutableListOf<String>()
    override fun get(key: String): String? = null
    override fun set(key: String, value: String, ttlSeconds: Long) = Unit
    override fun delete(key: String) = Unit
    override fun deleteByPrefix(prefix: String) {
        deletedPrefixes += prefix
    }
}

/**
 * MockK-заглушка [PasswordEncoder] для классических тестов: не выполняет реальный BCrypt,
 * а лишь помечает хеш префиксом. Тесты проверяют бизнес-логику, а не криптографию.
 */
fun mockPasswordEncoder(): PasswordEncoder =
    mockk<PasswordEncoder>().apply {
        every { encode(any()) } answers {
            "fake:{${firstArg<String>().length}}:${firstArg<String>()}"
        }
        every { matches(any(), any()) } answers {
            secondArg<String>() == "fake:{${firstArg<String>().length}}:${firstArg<String>()}"
        }
    }

fun videoConfig(
    maxDurationSeconds: Int = 120,
    maxFileSizeMb: Int = 10,
): VideoBusinessConfig = VideoBusinessConfig().apply {
    this.maxDurationSeconds = maxDurationSeconds
    this.allowedFormats = listOf("video/mp4")
    this.maxFileSizeMb = maxFileSizeMb
}

fun <T> Result<T>.valueOrThrow(): T = getOrThrow()

fun <T> Result<T>.errorOrThrow(): Error =
    exceptionOrNull() as? Error ?: error("Expected failure with Error, got success=${getOrNull()}")
