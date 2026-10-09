package com.snowkey.viday.model

data class PlaylistView(
    val id: Long,
    val name: String,
    val accessType: String,
    val ownerId: Long,
    val ownerName: String? = null,
    val previewUrl: String? = null,
)
