package com.vidayapi.dto.requests

import com.vidayapi.model.AccessType
import java.time.OffsetDateTime

data class UpdateStreamRequest(
    val name: String? = null,
    val description: String? = null,
    val source: String? = null,
    val accessType: AccessType? = null,
    val scheduledAt: OffsetDateTime? = null,
)
