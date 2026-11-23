package com.snowkey.viday.model

data class VideoView(
    val id: Long,
    val name: String,
    val preview: String,
    val description: String? = null,
    val source: String,
    val ownerId: Long
)
