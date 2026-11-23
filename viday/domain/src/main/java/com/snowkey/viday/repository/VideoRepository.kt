package com.snowkey.viday.repository

import com.snowkey.viday.model.VideosResult
import java.io.File

interface VideoRepository {
    suspend fun getVideos(userId: Long?, playlistId: Long?): VideosResult
    suspend fun uploadVideo(
        file: File,
        preview: File,
        name: String,
        description: String?,
        accessType: String
    ): VideosResult

    suspend fun getVideoById(videoId: Long, userId: Long? = null): VideosResult
}
