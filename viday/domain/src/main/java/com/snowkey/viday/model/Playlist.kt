package com.snowkey.viday.model

data class Playlist(
    val id: Long,
    val name: String,
    val accessType: String,
    val ownerId: Long
)
