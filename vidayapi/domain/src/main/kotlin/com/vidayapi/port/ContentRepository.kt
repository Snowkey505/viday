package com.vidayapi.port

import com.vidayapi.model.Page
import com.vidayapi.model.PageRequest
import com.vidayapi.model.Result
import com.vidayapi.model.Content
import com.vidayapi.model.MediaVariant
import com.vidayapi.model.Video

interface ContentRepository {
    fun findById(id: Int): Result<Content>
    fun findPublicContent(): Result<List<Content>>
    fun findPublicVideosPage(pageRequest: PageRequest): Result<Page<Video>>
    fun findByOwnerId(ownerId: Int): Result<List<Content>>
    fun findVideosByOwnerIdPage(ownerId: Int, pageRequest: PageRequest): Result<Page<Video>>
    fun findByPlaylistId(playlistId: Int): Result<List<Content>>
    fun findVideosByPlaylistIdPage(playlistId: Int, pageRequest: PageRequest): Result<Page<Video>>
    fun save(content: Content): Result<Content>
    fun saveVideo(video: Video): Result<Video>
    fun findVideoByContentId(contentId: Int): Result<Video>
    fun saveMediaVariant(variant: MediaVariant): Result<MediaVariant>
    fun findMediaVariantsByContentId(contentId: Int): Result<List<MediaVariant>>
    fun findSourceMediaVariant(contentId: Int): Result<MediaVariant>
    fun deleteById(contentId: Int, ownerId: Int): Result<Unit>
    fun deleteByIdAsAdmin(contentId: Int): Result<Unit>
    fun updateVideo(video: Video): Result<Unit>
}