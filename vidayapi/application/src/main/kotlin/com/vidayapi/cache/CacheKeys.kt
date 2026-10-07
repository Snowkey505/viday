package com.vidayapi.cache

object CacheKeys {
    const val PUBLIC_VIDEOS_PREFIX = "cache:videos:public:"
    const val VIDEO_BY_ID = "cache:video:"
    const val USER_BY_ID = "cache:user:"
    const val PLAYLIST_BY_ID = "cache:playlist:"
    const val LIVE_STREAMS_PREFIX = "cache:streams:live:"
    const val IS_FOLLOWING = "cache:follow:"

    fun publicVideos(page: Int, size: Int) = "${PUBLIC_VIDEOS_PREFIX}$page:$size"
    fun liveStreams(page: Int, size: Int) = "${LIVE_STREAMS_PREFIX}$page:$size"
    fun video(id: Int, viewerKey: String = "guest") = "$VIDEO_BY_ID$id:viewer:$viewerKey"
    fun videoPrefix(id: Int) = "$VIDEO_BY_ID$id:viewer:"
    fun user(id: Int) = "$USER_BY_ID$id"
    fun playlist(id: Int) = "$PLAYLIST_BY_ID$id"
    fun isFollowing(followerId: Int, followedId: Int) = "$IS_FOLLOWING$followerId:$followedId"
}

object CacheTtl {
    const val PUBLIC_VIDEOS_SEC = 300L
    const val VIDEO_SEC = 600L
    const val USER_SEC = 900L
    const val PLAYLIST_SEC = 600L
    const val LIVE_STREAMS_SEC = 30L
    const val FOLLOW_CHECK_SEC = 3600L
}
