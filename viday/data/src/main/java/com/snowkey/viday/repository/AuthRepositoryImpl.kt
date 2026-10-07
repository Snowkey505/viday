package com.snowkey.viday.repository

import com.snowkey.viday.api.ApiService
import com.snowkey.viday.api.TokenManager
import com.snowkey.viday.api.requests.LoginRequest
import com.snowkey.viday.api.requests.SignUpRequest
import com.snowkey.viday.model.AuthError
import com.snowkey.viday.model.AuthResult
import retrofit2.HttpException
import java.io.IOException

class AuthRepositoryImpl(
    private val apiService: ApiService,
    private val tokenManager: TokenManager
) : AuthRepository {

    override suspend fun login(login: String, password: String): AuthResult {
        return try {
            val response = apiService.login(LoginRequest(login, password))
            tokenManager.saveToken(response.token)
            AuthResult(
                error = AuthError.OK,
                userId = tokenManager.getUserId(),
                userName = login
            )
        } catch (e: HttpException) {
            if (e.code() == 401) {
                AuthResult(error = AuthError.INVALID_CREDENTIALS)
            } else {
                AuthResult(error = AuthError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            AuthResult(error = AuthError.NETWORK_ERROR)
        } catch (e: Exception) {
            AuthResult(error = AuthError.UNKNOWN_ERROR)
        }
    }

    override suspend fun signup(username: String, password: String): AuthResult {
        return try {
            val response = apiService.register(SignUpRequest(username, password))
            val loginResult = login(username, password)
            AuthResult(
                error = AuthError.OK,
                userId = response.id.toLong(),
                userName = response.username
            )
        } catch (e: HttpException) {
            if (e.code() == 409) {
                AuthResult(error = AuthError.INVALID_CREDENTIALS)
            } else {
                AuthResult(error = AuthError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            AuthResult(error = AuthError.NETWORK_ERROR)
        } catch (e: Exception) {
            AuthResult(error = AuthError.UNKNOWN_ERROR)
        }
    }

    override fun logout() {
        tokenManager.clearToken()
    }

    override fun getAuthorizedUserName(): String? {
        return tokenManager.getUsername()
    }
}
