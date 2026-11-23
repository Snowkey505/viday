package com.vidayapi.port

import com.vidayapi.model.Page
import com.vidayapi.model.PageRequest
import com.vidayapi.model.Result
import com.vidayapi.model.Stream
import com.vidayapi.model.StreamWithContent

interface StreamRepository {
    fun save(
        stream: Stream,
        contentName: String,
        description: String?,
        source: String?,
        ownerId: Int,
        accessTypeName: String,
    ): Result<StreamWithContent>
    fun findByContentId(contentId: Int): Result<StreamWithContent>
    fun findByOwnerId(ownerId: Int): Result<List<StreamWithContent>>
    fun findLiveStreams(): Result<List<StreamWithContent>>
    fun findLiveStreamsPage(pageRequest: PageRequest): Result<Page<StreamWithContent>>
    fun findByStreamKey(streamKey: String): Result<StreamWithContent>
    fun update(stream: Stream): Result<Stream>
    fun updateWithContent(streamWithContent: StreamWithContent): Result<StreamWithContent>
    fun delete(contentId: Int, ownerId: Int): Result<Unit>
    fun hasActiveLiveStream(ownerId: Int): Result<Boolean>
}
