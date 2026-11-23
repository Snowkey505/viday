package com.vidayapi.dto.requests

import com.vidayapi.model.AccessType

data class CreatePlaylistRequest(
    val name: String,
    val accessType: AccessType
)
