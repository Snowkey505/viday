package com.vidayapi.service

import com.vidayapi.cache.CacheKeys
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.map
import com.vidayapi.port.CachePort
import com.vidayapi.port.ContentRepository
import com.vidayapi.port.FileStoragePort

class ContentDeletionService(
    private val contentRepository: ContentRepository,
    private val fileStoragePort: FileStoragePort,
    private val cachePort: CachePort,
) {
    fun deleteVideoOwned(contentId: Int, ownerId: Int): Result<Unit> =
        contentRepository.findVideoByContentId(contentId).flatMap { video ->
            if (video.content.ownerId != ownerId) {
                Error.NotVideoOwner.left()
            } else {
                purgeContent(contentId, ownerId) {
                    contentRepository.deleteById(contentId, ownerId)
                }
            }
        }

    fun deleteContentAsAdmin(contentId: Int): Result<Unit> =
        contentRepository.findById(contentId).flatMap { content ->
            purgeContent(contentId, content.ownerId) {
                contentRepository.deleteByIdAsAdmin(contentId)
            }
        }

    private fun purgeContent(
        contentId: Int,
        ownerId: Int,
        dbDelete: () -> Result<Unit>,
    ): Result<Unit> =
        contentRepository.findMediaVariantsByContentId(contentId).flatMap { variants ->
            variants.forEach { deleteStoragePath(it.filePath) }
            contentRepository.findVideoByContentId(contentId).fold(
                onSuccess = { video ->
                    deleteStoragePath(video.preview)
                    deleteStoragePath(video.content.source)
                },
                onFailure = { Unit }
            )
            dbDelete().map { invalidateContentCache(contentId, ownerId) }
        }

    private fun invalidateContentCache(contentId: Int, ownerId: Int) {
        cachePort.deleteByPrefix(CacheKeys.videoPrefix(contentId))
        cachePort.deleteByPrefix(CacheKeys.PUBLIC_VIDEOS_PREFIX)
        cachePort.deleteByPrefix("cache:videos:owner:$ownerId")
        cachePort.deleteByPrefix(CacheKeys.LIVE_STREAMS_PREFIX)
    }

    private fun deleteStoragePath(path: String?) {
        if (path.isNullOrBlank() || path.contains("://")) return
        val slashIndex = path.indexOf('/')
        if (slashIndex <= 0) return
        fileStoragePort.deleteFile(path.substring(0, slashIndex), path.substring(slashIndex + 1))
    }
}
