package com.vidayapi.port

import com.vidayapi.model.ContentViewStats
import com.vidayapi.model.ContentViewStatsEntry
import com.vidayapi.model.Result

interface ViewStatsRepository {
    fun findAll(): Result<List<ContentViewStatsEntry>>
    fun findByContentId(contentId: Int): Result<ContentViewStatsEntry>
    fun findTopByViewCount(limit: Int): Result<List<ContentViewStatsEntry>>
}
