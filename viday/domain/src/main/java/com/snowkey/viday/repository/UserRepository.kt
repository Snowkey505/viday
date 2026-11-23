package com.snowkey.viday.repository

import com.snowkey.viday.model.UserResult

interface UserRepository {
    suspend fun getUserById(userId: Long): UserResult
    suspend fun followCreator(creatorUserId: Long): UserResult
    suspend fun unfollowCreator(creatorUserId: Long): UserResult
}
