package com.vidayapi.classic

import com.vidayapi.dto.requests.UploadVideoRequest
import com.vidayapi.model.AccessType
import com.vidayapi.usecase.RegisterUserUseCase
import com.vidayapi.usecase.UploadVideoUseCase
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.slf4j.helpers.NOPLogger
import org.springframework.mock.web.MockMultipartFile

@Tag("offline")
@Epic("Classic (Detroit)")
@Feature("Загрузка видео")
class UploadVideoClassicTest : PerTestJdbcFixture {

    override val db = ClassicJdbc()
    private val storage = RecordingFileStorage()
    private val cache = MapCache()
    private val register = RegisterUserUseCase(db.users, mockPasswordEncoder())
    private val useCase = UploadVideoUseCase(
        db.content,
        storage,
        FixedVideoProcessor(),
        videoConfig(),
        cache,
        NOPLogger.NOP_LOGGER,
    )

    @BeforeEach
    fun clearRecordingFakes() {
        storage.uploaded.clear()
        cache.deletedPrefixes.clear()
    }

    @Test
    @Story("Загрузка пишет content, video и media_variant через JdbcContentRepository")
    @Description(
        "Полный пайплайн: валидация файла, INSERT content RETURNING id, загрузка в in-memory storage, " +
                "INSERT video ON CONFLICT, INSERT media_variant. PostgreSQL не используется — только подмена DataSource.",
    )
    fun `upload persists content video and source variant`() {
        val owner = register.execute("creator", "password123").valueOrThrow()
        val request = UploadVideoRequest(
            name = "Clip",
            description = "demo",
            accessType = AccessType.PUBLIC,
            file = MockMultipartFile("file", "clip.mp4", "video/mp4", ByteArray(512)),
            preview = MockMultipartFile("preview", "p.jpg", "image/jpeg", byteArrayOf(1, 2, 3)),
        )

        val ownerId = requireNotNull(owner.id) { "owner.id is null after registration" }
        val video = useCase.execute(request, ownerId).valueOrThrow()

        assertThat(video.content.id).isNotNull()
        assertThat(video.content.name == "Clip")
        assertThat(video.content.ownerId == ownerId)
        assertThat(video.durationSeconds == 12)
        assertThat(video.preview).startsWith("previews/")
        assertThat(storage.uploaded).hasSize(2)
        assertThat(db.dataSource.catalog.rows("content")).hasSize(1)
        assertThat(db.dataSource.catalog.rows("video")).hasSize(1)
        assertThat(db.dataSource.catalog.rows("media_variant")).hasSize(1)
        assertThat(db.dataSource.catalog.rows("media_variant").single()["is_source"] == true)
        assertThat(cache.deletedPrefixes).isNotEmpty()
    }
}
