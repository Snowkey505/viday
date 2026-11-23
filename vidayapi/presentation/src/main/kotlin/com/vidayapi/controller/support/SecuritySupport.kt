package com.vidayapi.controller.support

import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.Role
import com.vidayapi.model.left
import com.vidayapi.model.map
import com.vidayapi.service.AuthService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.userdetails.UserDetails

fun resolveUserId(userDetails: UserDetails?): Int? =
    userDetails?.username?.toIntOrNull()

fun AuthService.authenticatedUser(userDetails: UserDetails?): Result<Pair<Int, Role>> {
    val userId = resolveUserId(userDetails) ?: return Error.Unauthorized.left()
    return getRoleForUserId(userId).map { role -> userId to role }
}

fun unauthorizedResponse(): ResponseEntity<Any> =
    ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

fun forbiddenResponse(): ResponseEntity<Any> =
    ResponseEntity.status(403).body(mapOf("error" to "Forbidden"))
