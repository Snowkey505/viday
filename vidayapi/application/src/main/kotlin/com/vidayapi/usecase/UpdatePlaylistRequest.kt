package com.vidayapi.usecase

import com.vidayapi.model.AccessType

data class UpdatePlaylistRequest(
    val name: String,
    val accessType: AccessType,
)
