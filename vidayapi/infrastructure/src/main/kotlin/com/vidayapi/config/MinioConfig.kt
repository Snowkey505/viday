package com.vidayapi.config

import io.minio.MinioClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MinioConfig {

    @Value("\${minio.endpoint:http://localhost:9000}")
    private lateinit var endpoint: String

    @Value("\${minio.access-key:minioadmin}")
    private lateinit var accessKey: String

    @Value("\${minio.secret-key:minioadmin}")
    private lateinit var secretKey: String

    @Value("\${minio.region:us-east-1}")
    private lateinit var region: String

    private val logger = LoggerFactory.getLogger(MinioConfig::class.java)

    @Bean
    fun minioClient(): MinioClient {
        logger.info("Creating MinIO client: endpoint={}, region={}", endpoint, region)
        return MinioClient.builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .region(region)
            .build()
    }
}
