package com.snowkey.viday.usecase.video

import com.snowkey.viday.model.VideosResult
import com.snowkey.viday.repository.VideoRepository

class GetVideoByIdUseCase(
    private val repository: VideoRepository
) {
    suspend operator fun invoke(videoId: Long, userId: Long? = null): VideosResult {
        return repository.getVideoById(videoId, userId)
    }
}
