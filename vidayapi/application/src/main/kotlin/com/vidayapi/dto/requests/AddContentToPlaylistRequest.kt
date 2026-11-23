package com.vidayapi.dto.requests

data class AddContentToPlaylistRequest(
    val contentId: Long,
    val position: Int? = null
)
