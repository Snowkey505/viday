package com.snowkey.viday.model

data class Video(
    val id: Long,
    val name: String,
    val description: String? = null,
    val source: String,
    val preview: String,
    val ownerId: Long
)
