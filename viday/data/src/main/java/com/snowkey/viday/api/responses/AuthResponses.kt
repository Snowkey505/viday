package com.snowkey.viday.api.responses

import com.google.gson.annotations.SerializedName

data class LoginResponse(
    val token: String
)

data class UserResponse(
    @SerializedName("id")
    val id: Long,

    @SerializedName("username")
    val username: String,

    @SerializedName("role")
    val role: String,

    @SerializedName("createdAt")
    val createdAt: String?
)
