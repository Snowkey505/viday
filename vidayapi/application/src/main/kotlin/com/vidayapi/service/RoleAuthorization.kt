package com.vidayapi.service

import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.Role
import com.vidayapi.model.left
import com.vidayapi.model.right

object RoleAuthorization {
    fun requireAdmin(role: Role): Result<Unit> =
        if (role == Role.ADMIN) Unit.right() else Error.Forbidden.left()

    fun requireAnalyst(role: Role): Result<Unit> =
        if (role == Role.ANALYST || role == Role.ADMIN) Unit.right() else Error.Forbidden.left()
}
