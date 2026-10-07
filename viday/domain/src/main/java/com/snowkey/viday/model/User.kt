package com.snowkey.viday.model

data class User(
    val id: Long,
    val username: String,
    val role: String,
    val createdAt: String?
)
