package com.vidayapi.usecase

import com.vidayapi.dto.requests.UpdateVideoRequest
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.Video
import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.port.ContentRepository
import org.springframework.stereotype.Component

@Component
class UpdateVideoUseCase(
    private val contentRepository: ContentRepository,
) {
    fun execute(
        videoId: Int,
        userId: Int,
        request: UpdateVideoRequest,
    ): Result<Video> {
        val video = contentRepository.findVideoByContentId(videoId).fold(
            onSuccess = { it },
            onFailure = { return Error.NotFound.left() }
        )

        if (video.content.ownerId != userId) {
            return Error.NotVideoOwner.left()
        }

        val updatedContent = video.content.copy(
            name = request.name ?: video.content.name,
            description = request.description ?: video.content.description,
            accessType = request.accessType ?: video.content.accessType,
        )

        val updatedVideo = video.copy(
            content = updatedContent
        )

        contentRepository.updateVideo(updatedVideo).fold(
            onSuccess = { },
            onFailure = { return Error.InvalidInput.left() }
        )

        return updatedVideo.right()
    }
}
