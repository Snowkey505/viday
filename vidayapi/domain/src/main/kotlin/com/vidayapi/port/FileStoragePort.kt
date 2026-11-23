package com.vidayapi.port

import java.io.InputStream
import com.vidayapi.model.Result

interface FileStoragePort {
    fun uploadFile(bucketName: String, objectName: String, inputStream: InputStream,
                   contentType: String, size: Long): Result<String>
    fun getFileUrl(bucketName: String, objectName: String): Result<String>
    fun deleteFile(bucketName: String, objectName: String): Result<Unit>
}
