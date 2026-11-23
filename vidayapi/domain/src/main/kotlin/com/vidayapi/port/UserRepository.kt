package com.vidayapi.port

import com.vidayapi.model.Result
import com.vidayapi.model.User

interface UserRepository {
    fun findById(id: Int): Result<User?>
    fun findByUsername(username: String): Result<User?>
    fun existsByUsername(username: String): Result<Boolean>
    fun save(user: User): Result<User>
    fun update(user: User): Result<User>
    fun follow(followingUserId: Int, followedUserId: Int): Result<Unit>
    fun unfollow(followingUserId: Int, followedUserId: Int): Result<Unit>
    fun deleteById(userId: Int): Result<Unit>
}
