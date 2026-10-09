package com.vidayapi.model

import java.time.OffsetDateTime

data class PlaylistItem(
    val contentId: Int,
    val playlistId: Int,
    val position: Int,
    val addedAt: OffsetDateTime = OffsetDateTime.now()
)
