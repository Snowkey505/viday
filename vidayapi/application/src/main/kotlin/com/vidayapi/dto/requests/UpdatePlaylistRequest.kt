package com.vidayapi.dto.requests

import com.vidayapi.model.AccessType

data class UpdatePlaylistRequest(
    val name: String?,
    val accessType: AccessType?
)
