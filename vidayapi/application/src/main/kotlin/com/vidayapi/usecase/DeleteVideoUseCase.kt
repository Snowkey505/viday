package com.vidayapi.usecase

import com.vidayapi.model.Result
import com.vidayapi.service.ContentDeletionService

class DeleteVideoUseCase(
    private val contentDeletionService: ContentDeletionService,
) {
    fun execute(contentId: Int, ownerId: Int): Result<Unit> =
        contentDeletionService.deleteVideoOwned(contentId, ownerId)
}
