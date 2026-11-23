package com.snowkey.viday.model

data class VideosResult(
    val error: VideoError = VideoError.NETWORK_ERROR,
    val videos: List<Video>? = null
)

data class AuthResult(
    val error: AuthError = AuthError.OK,
    val userId: Long? = null,
    val userName: String? = null
)

data class PlaylistResult(
    val error: PlaylistError = PlaylistError.OK,
    val playlists: List<Playlist>? = null
)

data class UserResult(
    val error: UserError = UserError.OK,
    val users: List<User>? = null
)
