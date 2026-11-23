package com.vidayapi.service

import com.vidayapi.dto.responses.VideoResponse
import com.vidayapi.model.Video
import com.vidayapi.port.FileStoragePort

class VideoResponseMapper(
    private val fileStoragePort: FileStoragePort,
) {
    fun toResponse(video: Video): VideoResponse =
        VideoResponse(
            id = video.content.id ?: 0,
            name = video.content.name,
            description = video.content.description,
            source = toExternalUrl(video.content.source),
            preview = toExternalUrl(video.preview),
            durationSeconds = video.durationSeconds,
            accessType = video.content.accessType.name,
            ownerId = video.content.ownerId,
        )

    private fun toExternalUrl(storagePath: String?): String {
        val path = storagePath.orEmpty()
        if (path.isBlank()) return ""
        if (path.contains("://")) return path

        val slashIndex = path.indexOf('/')
        if (slashIndex <= 0 || slashIndex == path.lastIndex) return path

        val bucketName = path.substring(0, slashIndex)
        val objectName = path.substring(slashIndex + 1)
        return fileStoragePort.getFileUrl(bucketName, objectName).fold(
            onSuccess = { it },
            onFailure = { path }
        )
    }
}
