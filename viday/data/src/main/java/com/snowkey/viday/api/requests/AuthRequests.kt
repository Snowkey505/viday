package com.snowkey.viday.api.requests

data class LoginRequest(
    val username: String,
    val password: String
)

data class SignUpRequest(
    val username: String,
    val password: String
)
