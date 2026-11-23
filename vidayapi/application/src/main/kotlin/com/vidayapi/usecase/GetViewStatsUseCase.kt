package com.vidayapi.usecase

import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.ContentViewStatsEntry
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.port.UserRepository
import com.vidayapi.port.ViewStatsRepository
import com.vidayapi.service.RoleAuthorization

class GetViewStatsUseCase(
    private val userRepository: UserRepository,
    private val viewStatsRepository: ViewStatsRepository,
) {
    fun listAll(requestingUserId: Int): Result<List<ContentViewStatsEntry>> =
        authorize(requestingUserId).flatMap { viewStatsRepository.findAll() }

    fun getByContentId(requestingUserId: Int, contentId: Int): Result<ContentViewStatsEntry> =
        authorize(requestingUserId).flatMap { viewStatsRepository.findByContentId(contentId) }

    fun getTop(requestingUserId: Int, limit: Int): Result<List<ContentViewStatsEntry>> =
        authorize(requestingUserId).flatMap {
            val safeLimit = limit.coerceIn(1, 100)
            viewStatsRepository.findTopByViewCount(safeLimit)
        }

    private fun authorize(requestingUserId: Int): Result<Unit> =
        userRepository.findById(requestingUserId).flatMap { user ->
            if (user == null) {
                Error.NotFound.left()
            } else {
                RoleAuthorization.requireAnalyst(user.role)
            }
        }
}
