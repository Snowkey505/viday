package com.snowkey.viday.repository

import com.snowkey.viday.api.ApiService
import com.snowkey.viday.api.responses.UserResponse
import com.snowkey.viday.model.User
import com.snowkey.viday.model.UserError
import com.snowkey.viday.model.UserResult
import com.snowkey.viday.api.TokenManager
import java.io.IOException
import retrofit2.HttpException

class UserRepositoryImpl(
    private val apiService: ApiService,
    private val tokenManager: TokenManager
) : UserRepository {

    override suspend fun getUserById(userId: Long): UserResult {
        val token = tokenManager.getToken()
        return try {
            val response = apiService.getUserById(
                userId = userId,
                authorization = token?.let { "Bearer $it" }
            )

            UserResult(
                error = UserError.OK,
                users = listOf(response.toUser())
            )
        } catch (e: HttpException) {
            when (e.code()) {
                401, 403 -> UserResult(error = UserError.ACCESS_ERROR)
                404 -> UserResult(error = UserError.NOT_FOUND)
                else -> UserResult(error = UserError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            UserResult(error = UserError.NETWORK_ERROR)
        } catch (e: Exception) {
            UserResult(error = UserError.UNKNOWN_ERROR)
        }
    }

    private fun UserResponse.toUser(): User {
        return User(
            id = id,
            username = username,
            role = role,
            createdAt = createdAt
        )
    }

    override suspend fun followCreator(creatorUserId: Long): UserResult {
        val token = tokenManager.getToken() ?: return UserResult(error = UserError.ACCESS_ERROR)
        return try {
            apiService.followCreator(token = "Bearer $token", userId = creatorUserId)
            UserResult(error = UserError.OK)
        } catch (e: HttpException) {
            when (e.code()) {
                401, 403 -> UserResult(error = UserError.ACCESS_ERROR)
                404 -> UserResult(error = UserError.NOT_FOUND)
                409 -> UserResult(error = UserError.ALREADY_FOLLOWING)
                in 400..499 -> UserResult(error = UserError.UNKNOWN_ERROR)
                else -> UserResult(error = UserError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            UserResult(error = UserError.UNKNOWN_ERROR)
        } catch (_: Exception) {
            UserResult(error = UserError.UNKNOWN_ERROR)
        }
    }

    override suspend fun unfollowCreator(creatorUserId: Long): UserResult {
        val token = tokenManager.getToken() ?: return UserResult(error = UserError.ACCESS_ERROR)
        return try {
            apiService.unfollowCreator(token = "Bearer $token", userId = creatorUserId)
            UserResult(error = UserError.OK)
        } catch (e: HttpException) {
            when (e.code()) {
                401, 403 -> UserResult(error = UserError.ACCESS_ERROR)
                404 -> UserResult(error = UserError.NOT_FOUND)
                else -> UserResult(error = UserError.SERVER_ERROR)
            }
        } catch (e: IOException) {
            UserResult(error = UserError.UNKNOWN_ERROR)
        } catch (_: Exception) {
            UserResult(error = UserError.UNKNOWN_ERROR)
        }
    }
}
