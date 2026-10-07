package com.snowkey.viday.api

import com.snowkey.viday.api.requests.AddContentToPlaylistRequest
import com.snowkey.viday.api.requests.CreatePlaylistRequest
import com.snowkey.viday.api.requests.LoginRequest
import com.snowkey.viday.api.requests.SignUpRequest
import com.snowkey.viday.api.requests.UpdatePlaylistRequest
import com.snowkey.viday.api.responses.LoginResponse
import com.snowkey.viday.api.responses.PageResponse
import com.snowkey.viday.api.responses.PlaylistResponse
import com.snowkey.viday.api.responses.UserResponse
import com.snowkey.viday.api.responses.VideoResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("api/auth/register")
    suspend fun register(@Body request: SignUpRequest): UserResponse

    @GET("api/videos/{id}")
    suspend fun getVideoById(
        @Path("id") videoId: Long,
        @Header("Authorization") authorization: String?
    ): VideoResponse

    @GET("api/videos")
    suspend fun getVideos(
        @Header("Authorization") authorization: String?,
        @Query("userId") userId: Long?,
        @Query("playlistId") playlistId: Long?
    ): PageResponse<VideoResponse>

    @Multipart
    @POST("api/videos/upload")
    suspend fun uploadVideo(
        @Header("Authorization") token: String,
        @Part file: MultipartBody.Part,
        @Part preview: MultipartBody.Part,
        @Part("name") name: RequestBody,
        @Part("description") description: RequestBody?,
        @Part("accessType") accessType: RequestBody
    ): VideoResponse

    @POST("api/playlists")
    suspend fun createPlaylist(
        @Header("Authorization") token: String,
        @Body request: CreatePlaylistRequest
    ): PlaylistResponse

    @GET("api/playlists")
    suspend fun getPlaylists(
        @Header("Authorization") authorization: String?
    ): PageResponse<PlaylistResponse>

    @GET("api/playlists/{playlistId}")
    suspend fun getPlaylistById(
        @Path("playlistId") playlistId: Long,
        @Header("Authorization") authorization: String?
    ): PlaylistResponse

    @PUT("api/playlists/{playlistId}")
    suspend fun updatePlaylist(
        @Path("playlistId") playlistId: Long,
        @Header("Authorization") token: String,
        @Body request: UpdatePlaylistRequest
    ): PlaylistResponse

    @DELETE("api/playlists/{playlistId}")
    suspend fun deletePlaylist(
        @Path("playlistId") playlistId: Long,
        @Header("Authorization") token: String
    )

    @POST("api/playlists/{playlistId}/contents")
    suspend fun addContentToPlaylist(
        @Path("playlistId") playlistId: Long,
        @Header("Authorization") token: String,
        @Body request: AddContentToPlaylistRequest
    )

    @DELETE("api/playlists/{playlistId}/contents/{contentId}")
    suspend fun removeContentFromPlaylist(
        @Path("playlistId") playlistId: Long,
        @Path("contentId") contentId: Long,
        @Header("Authorization") token: String
    )

    @POST("api/users/{userId}/follow")
    suspend fun followCreator(
        @Header("Authorization") token: String,
        @Path("userId") userId: Long
    )

    @DELETE("api/users/{userId}/follow")
    suspend fun unfollowCreator(
        @Header("Authorization") token: String,
        @Path("userId") userId: Long
    )

    @GET("api/playlists/available")
    suspend fun getAvailablePlaylists(
        @Header("Authorization") authorization: String?
    ): List<PlaylistResponse>

    @GET("api/users/{userId}")
    suspend fun getUserById(
        @Path("userId") userId: Long,
        @Header("Authorization") authorization: String?
    ): UserResponse
}
