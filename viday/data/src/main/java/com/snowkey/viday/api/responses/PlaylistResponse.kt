package com.snowkey.viday.api.responses

data class PlaylistResponse(
    val id: Long,
    val name: String,
    val accessType: String,
    val ownerId: Long
)
