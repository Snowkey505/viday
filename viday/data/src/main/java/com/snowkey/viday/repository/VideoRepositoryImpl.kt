package com.snowkey.viday.repository

import android.util.Log
import com.snowkey.viday.api.ApiService
import com.snowkey.viday.api.TokenManager
import com.snowkey.viday.api.responses.VideoResponse
import com.snowkey.viday.model.Video
import com.snowkey.viday.model.VideoError
import com.snowkey.viday.model.VideosResult
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import retrofit2.HttpException
import java.io.IOException

class VideoRepositoryImpl(
    private val apiService: ApiService,
    private val tokenManager: TokenManager
) : VideoRepository {
    override suspend fun getVideos(userId: Long?, playlistId: Long?): VideosResult {
        val token = tokenManager.getToken()

        return try {
            val response = apiService.getVideos(
                authorization = token?.let { "Bearer $it" },
                userId = userId,
                playlistId = playlistId
            )

            val videos = response.items.map { videoResponse ->
                Log.i("VIDEOS", videoResponse.source)
                Video(
                    id = videoResponse.id.toLong(),
                    name = videoResponse.name,
                    description = videoResponse.description,
                    source = videoResponse.source,
                    preview = videoResponse.preview,
                    ownerId = videoResponse.ownerId.toLong()
                )
            }

            VideosResult(error = VideoError.OK, videos = videos)
        } catch (e: HttpException) {
            if (e.code() == 403 || e.code() == 401) {
                VideosResult(error = VideoError.ACCESS_ERROR)
            } else {
                VideosResult(error = VideoError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            VideosResult(error = VideoError.NETWORK_ERROR)
        } catch (e: Exception) {
            VideosResult(error = VideoError.UNKNOWN_ERROR)
        }
    }

    override suspend fun uploadVideo(
        file: File,
        preview: File,
        name: String,
        description: String?,
        accessType: String
    ): VideosResult {
        val token = tokenManager.getToken()
        if (token == null) {
            return VideosResult(error = VideoError.ACCESS_ERROR)
        }

        return try {
            val requestFile = file.asRequestBody("video/mp4".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
            val requestPreview = preview.asRequestBody("image/*".toMediaTypeOrNull())
            val previewBody = MultipartBody.Part.createFormData("preview", preview.name, requestPreview)

            val namePart = name.toRequestBody("text/plain".toMediaTypeOrNull())
            val accessTypePart = accessType.toRequestBody("text/plain".toMediaTypeOrNull())
            val descriptionPart = description?.toRequestBody("text/plain".toMediaTypeOrNull())

            val response = apiService.uploadVideo(
                token = "Bearer $token",
                file = body,
                preview = previewBody,
                name = namePart,
                description = descriptionPart,
                accessType = accessTypePart
            )

            val video = Video(
                id = response.id.toLong(),
                name = response.name,
                description = response.description,
                source = response.source,
                preview = response.preview,
                ownerId = response.ownerId.toLong()
            )

            VideosResult(error = VideoError.OK, videos = listOf(video))
        } catch (e: HttpException) {
            if (e.code() == 403 || e.code() == 401) {
                VideosResult(error = VideoError.ACCESS_ERROR)
            } else {
                VideosResult(error = VideoError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            VideosResult(error = VideoError.NETWORK_ERROR)
        } catch (e: Exception) {
            VideosResult(error = VideoError.UNKNOWN_ERROR)
        }
    }

    override suspend fun getVideoById(videoId: Long, userId: Long?): VideosResult {
        val token = tokenManager.getToken()
        return try {
            val response = apiService.getVideoById(
                videoId = videoId,
                authorization = token?.let { "Bearer $it" }
            )

            VideosResult(
                error = VideoError.OK,
                videos = listOf(response.toVideo())
            )
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> VideosResult(error = VideoError.ACCESS_ERROR)
                403 -> VideosResult(error = VideoError.ACCESS_ERROR)
                404 -> VideosResult(error = VideoError.SERVER_ERROR)
                else -> VideosResult(error = VideoError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            VideosResult(error = VideoError.NETWORK_ERROR)
        } catch (e: Exception) {
            VideosResult(error = VideoError.UNKNOWN_ERROR)
        }
    }

    private fun VideoResponse.toVideo(): Video {
        return Video(
            id = id,
            name = name,
            description = description,
            source = source,
            preview = preview,
            ownerId = ownerId
        )
    }
}
