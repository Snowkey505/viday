package com.vidayapi.dto.requests

import com.vidayapi.model.AccessType
import org.springframework.web.multipart.MultipartFile

data class UploadVideoRequest(
    val name: String,
    val description: String?,
    val accessType: AccessType,
    val file: MultipartFile,
    val preview: MultipartFile
)
