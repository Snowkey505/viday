package com.snowkey.viday.api.requests

data class CreatePlaylistRequest(
    val name: String,
    val accessType: String
)

data class UpdatePlaylistRequest(
    val name: String?,
    val accessType: String?
)

data class AddContentToPlaylistRequest(
    val contentId: Long,
    val position: Int? = null
)
