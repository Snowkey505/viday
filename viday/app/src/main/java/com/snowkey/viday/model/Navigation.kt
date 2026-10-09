package com.snowkey.viday.model

enum class Navigation(val route: String) {
    LOGIN("login"),
    SIGNUP("signup"),
    PLAYLISTS("playlists/{userId}"),
    PROFILE("profile"),
    VIDEOS("videos/{userId}/{playlistId}"),
    VIDEO("video/{userId}/{videoId}/{playlistId}");

    companion object {
        fun createPlaylistsRoute(userId: Long?): String {
            return "playlists/${userId ?: -1}"
        }
        fun createVideosRoute(userId: Long?, playlistId: Long?): String {
            return when {
                userId != null && playlistId != null -> "videos/$userId/$playlistId"
                userId != null -> "videos/$userId/-1"
                playlistId != null -> "videos/-1/$playlistId"
                else -> "videos/-1/-1"
            }
        }

        fun createVideoRoute(userId: Long?, videoId: Long?, playlistId: Long?): String {
            return "video/${userId ?: -1}/${videoId ?: -1}/${playlistId ?: -1}"
        }
    }
}
