package com.snowkey.viday.model

import kotlinx.serialization.Serializable

@Serializable
data class AuthData(
    val login: String,
    val password: String
)
