package com.vidayapi.usecase

import com.vidayapi.cache.CacheKeys
import com.vidayapi.config.VideoBusinessConfig
import com.vidayapi.port.CachePort
import com.vidayapi.dto.requests.UploadVideoRequest
import com.vidayapi.model.Codec
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.Content
import com.vidayapi.model.ContentType
import com.vidayapi.model.MediaVariant
import com.vidayapi.model.Video
import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.port.ContentRepository
import com.vidayapi.port.FileStoragePort
import com.vidayapi.port.VideoProcessor
import org.slf4j.Logger
import java.util.UUID

class UploadVideoUseCase(
    private val contentRepository: ContentRepository,
    private val fileStoragePort: FileStoragePort,
    private val videoProcessor: VideoProcessor,
    private val videoConfig: VideoBusinessConfig,
    private val cachePort: CachePort,
    private val logger: Logger,
) {

    fun execute(request: UploadVideoRequest, ownerId: Int): Result<Video> {
        logger.info("Starting video upload: name={}, size={}", request.name, request.file.size)

        val contentType = request.file.contentType ?: "video/mp4"
        if (contentType !in videoConfig.allowedFormats) {
            logger.warn("Invalid format: {} (allowed: {})", contentType, videoConfig.allowedFormats)
            return Error.InvalidInput.left()
        }
        val previewContentType = request.preview.contentType ?: ""
        if (!previewContentType.startsWith("image/")) {
            logger.warn("Invalid preview format: {}", previewContentType)
            return Error.InvalidInput.left()
        }

        val maxSizeBytes = videoConfig.maxFileSizeMb * 1024L * 1024L
        if (request.file.size > maxSizeBytes) {
            logger.warn("File too large: {} bytes (max: {} MB)", request.file.size, videoConfig.maxFileSizeMb)
            return Error.InvalidInput.left()
        }

        val content = Content(
            type = ContentType.VIDEO,
            name = request.name,
            description = request.description,
            source = null,
            ownerId = ownerId,
            accessType = request.accessType
        )

        val savedContent = contentRepository.save(content).fold(
            onSuccess = { it },
            onFailure = { error ->
                logger.error("Failed to save content: {}", error)
                return (error as Error).left()
            }
        )

        logger.info("Content saved with ID: {}", savedContent.id)

        val videoObjectName = "${savedContent.id}/${UUID.randomUUID()}.mp4"
        logger.info("Uploading video to MinIO: bucket=videos, object={}", videoObjectName)

        val uploadResult = fileStoragePort.uploadFile(
            bucketName = "videos",
            objectName = videoObjectName,
            inputStream = request.file.inputStream,
            contentType = contentType,
            size = request.file.size
        )

        val sourcePath = uploadResult.fold(
            onSuccess = { value ->
                logger.info("File uploaded successfully: {}", value)
                value
            },
            onFailure = { error ->
                logger.error("Failed to upload to MinIO: {}", error)
                return Error.StorageFailure.left()
            }
        )

        val previewExtension = request.preview.originalFilename
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() }
            ?: "jpg"
        val previewObjectName = "${savedContent.id}/${UUID.randomUUID()}.$previewExtension"
        val previewUploadResult = fileStoragePort.uploadFile(
            bucketName = "previews",
            objectName = previewObjectName,
            inputStream = request.preview.inputStream,
            contentType = previewContentType,
            size = request.preview.size
        )

        val previewPath = previewUploadResult.fold(
            onSuccess = { it },
            onFailure = { error ->
                logger.error("Failed to upload preview to MinIO: {}", error)
                fileStoragePort.deleteFile("videos", videoObjectName)
                return Error.StorageFailure.left()
            }
        )

        logger.info("Analyzing video...")
        val videoMetadata = videoProcessor.analyze(
            request.file.inputStream,
            request.file.size
        ).fold(
            onSuccess = { it },
            onFailure = { error ->
                logger.error("Video analysis failed: {}", error)
                fileStoragePort.deleteFile("videos", videoObjectName)
                fileStoragePort.deleteFile("previews", previewObjectName)
                return (error as Error).left()
            }
        )

        logger.info(
            "Video analysis: duration={}s, {}x{}",
            videoMetadata.durationSeconds, videoMetadata.width, videoMetadata.height
        )

        if (videoMetadata.durationSeconds > videoConfig.maxDurationSeconds) {
            logger.warn("Video too long: {}s (max: {}s)", videoMetadata.durationSeconds, videoConfig.maxDurationSeconds)
            fileStoragePort.deleteFile("videos", videoObjectName)
            fileStoragePort.deleteFile("previews", previewObjectName)
            return Error.InvalidInput.left()
        }

        val video = Video(
            content = savedContent.copy(source = sourcePath),
            durationSeconds = videoMetadata.durationSeconds,
            preview = previewPath
        )
        val savedVideo = contentRepository.saveVideo(video).fold(
            onSuccess = { it },
            onFailure = { error ->
                logger.error("Failed to save video: {}", error)
                fileStoragePort.deleteFile("videos", videoObjectName)
                fileStoragePort.deleteFile("previews", previewObjectName)
                return (error as Error).left()
            }
        )

        val sourceVariant = MediaVariant(
            contentId = savedContent.id!!,
            width = videoMetadata.width,
            height = videoMetadata.height,
            bitrate = videoMetadata.bitrate,
            codec = Codec.H264,
            isSource = true,
            filePath = sourcePath,
            fileSizeBytes = request.file.size
        )

        contentRepository.saveMediaVariant(sourceVariant).fold(
            onSuccess = { logger.info("Media variant saved") },
            onFailure = { error ->
                logger.error("Failed to save media variant: {}", error)
                fileStoragePort.deleteFile("videos", videoObjectName)
                fileStoragePort.deleteFile("previews", previewObjectName)
                return (error as Error).left()
            }
        )

        cachePort.deleteByPrefix(CacheKeys.PUBLIC_VIDEOS_PREFIX)
        cachePort.deleteByPrefix("cache:videos:owner:$ownerId")

        logger.info("Video upload completed successfully")
        return savedVideo.right()
    }
}
