package com.vidayapi.dto.requests

import com.vidayapi.model.AccessType
import java.time.OffsetDateTime

data class CreateStreamRequest(
    val name: String,
    val description: String? = null,
    val source: String,
    val accessType: AccessType = AccessType.PUBLIC,
    val scheduledAt: OffsetDateTime? = null,
)
