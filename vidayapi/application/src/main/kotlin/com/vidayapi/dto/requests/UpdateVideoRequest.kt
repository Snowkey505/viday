package com.vidayapi.dto.requests

import com.vidayapi.model.AccessType

data class UpdateVideoRequest(
    val name: String? = null,
    val description: String? = null,
    val accessType: AccessType? = null,
)
