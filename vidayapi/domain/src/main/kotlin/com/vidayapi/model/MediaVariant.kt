package com.vidayapi.model

import java.time.OffsetDateTime

data class MediaVariant(
    val id: Int? = null,
    val contentId: Int,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val codec: Codec,
    val isSource: Boolean = false,
    val filePath: String,
    val fileSizeBytes: Long? = null,
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)

enum class Codec {
    H264, VP9, AV1, H265
}
