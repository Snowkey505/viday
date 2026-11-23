package com.vidayapi.config

import com.vidayapi.port.CachePort
import com.vidayapi.port.ContentRepository
import com.vidayapi.port.StreamRepository
import com.vidayapi.service.VideoResponseMapper
import com.vidayapi.usecase.ListLiveStreamsUseCase
import com.vidayapi.usecase.ListVideosUseCase
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration(proxyBeanMethods = false)
class PresentationUseCaseConfig {

    @Bean
    fun listVideosUseCase(
        contentRepository: ContentRepository,
        cachePort: CachePort,
        videoResponseMapper: VideoResponseMapper,
        objectMapper: ObjectMapper,
    ): ListVideosUseCase =
        ListVideosUseCase(contentRepository, cachePort, videoResponseMapper, objectMapper)

    @Bean
    fun listLiveStreamsUseCase(
        streamRepository: StreamRepository,
        cachePort: CachePort,
        objectMapper: ObjectMapper,
    ): ListLiveStreamsUseCase =
        ListLiveStreamsUseCase(streamRepository, cachePort, objectMapper)
}
