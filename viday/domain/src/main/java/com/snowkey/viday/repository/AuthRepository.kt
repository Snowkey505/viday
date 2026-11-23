package com.snowkey.viday.repository

import com.snowkey.viday.model.AuthResult

interface AuthRepository {
    suspend fun login(login: String, password: String): AuthResult
    suspend fun signup(username: String, password: String): AuthResult
    fun logout()
    fun getAuthorizedUserName(): String?
}
