package com.snowkey.viday.usecase.video

import com.snowkey.viday.model.VideoError
import com.snowkey.viday.model.VideosResult
import com.snowkey.viday.repository.VideoRepository
import java.io.File

class UploadVideoUseCase(
    private val videoRepository: VideoRepository
) {
    suspend operator fun invoke(
        file: File,
        preview: File,
        name: String,
        description: String?,
        accessType: String
    ): VideosResult {
        if (!file.exists()) {
            return VideosResult(error = VideoError.UNKNOWN_ERROR)
        }
        if (!preview.exists()) {
            return VideosResult(error = VideoError.UNKNOWN_ERROR)
        }
        if (name.isBlank()) {
            return VideosResult(error = VideoError.UNKNOWN_ERROR)
        }
        return videoRepository.uploadVideo(file, preview, name, description, accessType)
    }
}
