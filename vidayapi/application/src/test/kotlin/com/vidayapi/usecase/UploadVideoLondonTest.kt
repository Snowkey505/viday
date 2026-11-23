package com.vidayapi.usecase

import com.vidayapi.model.right
import com.vidayapi.config.VideoBusinessConfig
import com.vidayapi.dto.requests.UploadVideoRequest
import com.vidayapi.model.AccessType
import com.vidayapi.model.Codec
import com.vidayapi.model.Content
import com.vidayapi.model.ContentType
import com.vidayapi.model.Video
import com.vidayapi.port.CachePort
import com.vidayapi.port.ContentRepository
import com.vidayapi.port.FileStoragePort
import com.vidayapi.port.VideoMetadata
import com.vidayapi.port.VideoProcessor
import com.vidayapi.support.assertRight
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.slf4j.helpers.NOPLogger
import org.springframework.mock.web.MockMultipartFile

@Tag("offline")
@Epic("London")
@Feature("Загрузка видео")
class UploadVideoLondonTest {

    private val contentRepository = mockk<ContentRepository>()
    private val fileStorage = mockk<FileStoragePort>()
    private val processor = mockk<VideoProcessor>()
    private val cache = mockk<CachePort>(relaxUnitFun = true)
    private val config = VideoBusinessConfig(
        maxDurationSeconds = 60,
        allowedFormats = listOf("video/mp4"),
        maxFileSizeMb = 1,
    )
    private val useCase = UploadVideoUseCase(contentRepository, fileStorage, processor, config, cache, NOPLogger.NOP_LOGGER)

    @Test
    @Story("Моки портов: успешная загрузка проверяет взаимодействие, а не состояние БД")
    @Description(
        "Лондонский стиль: ContentRepository / FileStorage / VideoProcessor / Cache — mockk. " +
            "Проверяется порядок save → upload → analyze → saveVideo → saveMediaVariant и инвалидация кэша.",
    )
    fun `execute uploads and persists through mocked collaborators`() {
        val savedContent = Content(
            id = 42,
            type = ContentType.VIDEO,
            name = "Clip",
            description = "d",
            source = null,
            ownerId = 7,
            accessType = AccessType.PUBLIC,
        )
        every { contentRepository.save(any()) } returns savedContent.right()
        every { fileStorage.uploadFile(any(), any(), any(), any(), any()) } answers {
            "${firstArg<String>()}/${secondArg<String>()}".right()
        }
        every { processor.analyze(any(), any()) } returns VideoMetadata(10, 1280, 720, 2500).right()
        every { contentRepository.saveVideo(any()) } answers { firstArg<Video>().right() }
        every { contentRepository.saveMediaVariant(any()) } answers { firstArg<com.vidayapi.model.MediaVariant>().right() }

        val video = useCase.execute(request(), 7).assertRight()

        assertThat(video.content.id).isEqualTo(42)
        assertThat(video.durationSeconds).isEqualTo(10)
        verifyOrder {
            contentRepository.save(match { it.name == "Clip" && it.ownerId == 7 })
            fileStorage.uploadFile("videos", any(), any(), "video/mp4", 100)
            fileStorage.uploadFile("previews", any(), any(), "image/jpeg", 3)
            processor.analyze(any(), 100)
            contentRepository.saveVideo(match { it.content.id == 42 && it.durationSeconds == 10 })
            contentRepository.saveMediaVariant(match { it.contentId == 42 && it.codec == Codec.H264 && it.isSource })
            cache.deleteByPrefix(any())
            cache.deleteByPrefix(any())
        }
    }

    private fun request() = UploadVideoRequest(
        "Clip",
        "d",
        AccessType.PUBLIC,
        MockMultipartFile("file", "clip.mp4", "video/mp4", ByteArray(100)),
        MockMultipartFile("preview", "p.jpg", "image/jpeg", byteArrayOf(1, 2, 3)),
    )
}
