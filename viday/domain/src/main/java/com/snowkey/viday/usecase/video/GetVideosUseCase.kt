package com.snowkey.viday.usecase.video

import com.snowkey.viday.model.VideosResult
import com.snowkey.viday.repository.VideoRepository

class GetVideosUseCase(
    private val videoRepository: VideoRepository
) {
    suspend operator fun invoke(userId: Long? = null, playlistId: Long? = null): VideosResult {
        return videoRepository.getVideos(userId, playlistId)
    }
}
