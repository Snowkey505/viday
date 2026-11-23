package com.vidayapi.storage

import com.vidayapi.port.FileStoragePort
import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.left
import com.vidayapi.model.right
import io.minio.GetPresignedObjectUrlArgs
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.http.Method
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

@Configuration
class MinioFileStorageAdapter(
    private val minioClient: MinioClient,
    @Value("\${minio.public-endpoint:\${minio.endpoint:http://localhost:9000}}")
    private val publicEndpoint: String,
    @Value("\${minio.access-key:minioadmin}")
    private val accessKey: String,
    @Value("\${minio.secret-key:minioadmin}")
    private val secretKey: String,
    @Value("\${minio.region:us-east-1}")
    private val region: String
) : FileStoragePort {

    private val logger = LoggerFactory.getLogger(MinioFileStorageAdapter::class.java)
    private val publicMinioClient: MinioClient by lazy {
        MinioClient.builder()
            .endpoint(publicEndpoint)
            .credentials(accessKey, secretKey)
            .region(region)
            .build()
    }

    override fun uploadFile(
        bucketName: String,
        objectName: String,
        inputStream: InputStream,
        contentType: String,
        size: Long
    ): Result<String> {
        return try {
            logger.info("Checking bucket: {}", bucketName)

            val found = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build()
            )

            if (!found) {
                logger.info("Creating bucket: {}", bucketName)
                minioClient.makeBucket(
                    MakeBucketArgs.builder().bucket(bucketName).build()
                )
                logger.info("Bucket created successfully")
            }

            val bytes = inputStream.readBytes()
            logger.info("Uploading file: {} ({} bytes)", objectName, bytes.size)

            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .`object`(objectName)
                    .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1)
                    .contentType(contentType)
                    .build()
            )

            logger.info("File uploaded successfully: {}", objectName)
            "$bucketName/$objectName".right()

        } catch (e: Exception) {
            logger.error("MinIO upload failed: {}", e.message, e)
            Error.StorageFailure.left()
        }
    }

    override fun getFileUrl(bucketName: String, objectName: String): Result<String> {
        return try {
            val url = publicMinioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .bucket(bucketName)
                    .`object`(objectName)
                    .method(Method.GET)
                    .expiry(1, TimeUnit.HOURS)
                    .build()
            )
            url.right()
        } catch (e: Exception) {
            logger.error("Failed to get file URL: {}", e.message, e)
            Error.StorageFailure.left()
        }
    }

    override fun deleteFile(bucketName: String, objectName: String): Result<Unit> {
        return try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .`object`(objectName)
                    .build()
            )
            Unit.right()
        } catch (e: Exception) {
            logger.error("Failed to delete file: {}", e.message, e)
            Error.StorageFailure.left()
        }
    }
}
