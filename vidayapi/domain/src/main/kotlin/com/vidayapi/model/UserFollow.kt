package com.vidayapi.model

import java.time.OffsetDateTime

data class UserFollow(
    val followingUserId: Int,
    val followedUserId: Int,
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)
