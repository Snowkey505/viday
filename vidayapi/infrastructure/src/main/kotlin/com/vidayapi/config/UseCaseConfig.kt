package com.vidayapi.config

import com.vidayapi.port.CachePort
import com.vidayapi.port.ContentRepository
import com.vidayapi.port.FileStoragePort
import com.vidayapi.port.PasswordEncoder
import com.vidayapi.port.PlaylistRepository
import com.vidayapi.port.StreamRepository
import com.vidayapi.port.UserRepository
import com.vidayapi.port.VideoProcessor
import com.vidayapi.port.ViewStatsRepository
import com.vidayapi.service.ContentDeletionService
import com.vidayapi.service.FFmpegVideoProcessor
import com.vidayapi.service.VideoResponseMapper
import com.vidayapi.usecase.ActivateChannelUseCase
import com.vidayapi.usecase.AddContentToPlaylistUseCase
import com.vidayapi.usecase.AdminDeleteContentUseCase
import com.vidayapi.usecase.AdminDeletePlaylistUseCase
import com.vidayapi.usecase.AdminDeleteUserUseCase
import com.vidayapi.usecase.AssignAdminRoleUseCase
import com.vidayapi.usecase.AssignAnalystRoleUseCase
import com.vidayapi.usecase.CreatePlaylistUseCase
import com.vidayapi.usecase.CreateStreamUseCase
import com.vidayapi.usecase.DeleteStreamUseCase
import com.vidayapi.usecase.DeleteVideoUseCase
import com.vidayapi.usecase.FollowUserUseCase
import com.vidayapi.usecase.GetViewStatsUseCase
import com.vidayapi.usecase.RegisterUserUseCase
import com.vidayapi.usecase.StartStreamUseCase
import com.vidayapi.usecase.StopStreamUseCase
import com.vidayapi.usecase.UnfollowUserUseCase
import com.vidayapi.usecase.UpdateStreamUseCase
import com.vidayapi.usecase.UploadVideoUseCase
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class UseCaseConfig {
    @Bean
    fun registerUserUseCase(
        userRepository: UserRepository,
        passwordEncoder: PasswordEncoder,
    ): RegisterUserUseCase = RegisterUserUseCase(userRepository, passwordEncoder)

    @Bean
    fun activateChannelUseCase(userRepository: UserRepository): ActivateChannelUseCase =
        ActivateChannelUseCase(userRepository)

    @Bean
    fun assignAdminRoleUseCase(userRepository: UserRepository): AssignAdminRoleUseCase =
        AssignAdminRoleUseCase(userRepository)

    @Bean
    fun assignAnalystRoleUseCase(userRepository: UserRepository): AssignAnalystRoleUseCase =
        AssignAnalystRoleUseCase(userRepository)

    @Bean
    fun createPlaylistUseCase(playlistRepository: PlaylistRepository): CreatePlaylistUseCase =
        CreatePlaylistUseCase(playlistRepository)

    @Bean
    fun addContentToPlaylistUseCase(playlistRepository: PlaylistRepository): AddContentToPlaylistUseCase =
        AddContentToPlaylistUseCase(playlistRepository)

    @Bean
    fun followUserUseCase(userRepository: UserRepository): FollowUserUseCase =
        FollowUserUseCase(userRepository)

    @Bean
    fun unfollowUserUseCase(userRepository: UserRepository): UnfollowUserUseCase =
        UnfollowUserUseCase(userRepository)

    @Bean
    fun videoProcessor(): VideoProcessor = FFmpegVideoProcessor()

    @Bean
    fun contentDeletionService(
        contentRepository: ContentRepository,
        fileStoragePort: FileStoragePort,
        cachePort: CachePort,
    ): ContentDeletionService = ContentDeletionService(contentRepository, fileStoragePort, cachePort)

    @Bean
    fun uploadVideoUseCase(
        contentRepository: ContentRepository,
        fileStoragePort: FileStoragePort,
        videoProcessor: VideoProcessor,
        videoConfig: VideoBusinessConfig,
        cachePort: CachePort,
    ): UploadVideoUseCase =
        UploadVideoUseCase(
            contentRepository,
            fileStoragePort,
            videoProcessor,
            videoConfig,
            cachePort,
            LoggerFactory.getLogger(UploadVideoUseCase::class.java),
        )

    @Bean
    fun deleteVideoUseCase(contentDeletionService: ContentDeletionService): DeleteVideoUseCase =
        DeleteVideoUseCase(contentDeletionService)

    @Bean
    fun adminDeleteUserUseCase(userRepository: UserRepository): AdminDeleteUserUseCase =
        AdminDeleteUserUseCase(userRepository)

    @Bean
    fun adminDeleteContentUseCase(
        userRepository: UserRepository,
        contentDeletionService: ContentDeletionService,
    ): AdminDeleteContentUseCase = AdminDeleteContentUseCase(userRepository, contentDeletionService)

    @Bean
    fun adminDeletePlaylistUseCase(
        userRepository: UserRepository,
        playlistRepository: PlaylistRepository,
    ): AdminDeletePlaylistUseCase = AdminDeletePlaylistUseCase(userRepository, playlistRepository)

    @Bean
    fun getViewStatsUseCase(
        userRepository: UserRepository,
        viewStatsRepository: ViewStatsRepository,
    ): GetViewStatsUseCase = GetViewStatsUseCase(userRepository, viewStatsRepository)

    @Bean
    fun createStreamUseCase(
        streamRepository: StreamRepository,
        cachePort: CachePort,
    ): CreateStreamUseCase = CreateStreamUseCase(streamRepository, cachePort)

    @Bean
    fun updateStreamUseCase(
        streamRepository: StreamRepository,
        cachePort: CachePort,
    ): UpdateStreamUseCase = UpdateStreamUseCase(streamRepository, cachePort)

    @Bean
    fun startStreamUseCase(
        streamRepository: StreamRepository,
        cachePort: CachePort,
    ): StartStreamUseCase = StartStreamUseCase(streamRepository, cachePort)

    @Bean
    fun stopStreamUseCase(
        streamRepository: StreamRepository,
        cachePort: CachePort,
    ): StopStreamUseCase = StopStreamUseCase(streamRepository, cachePort)

    @Bean
    fun deleteStreamUseCase(
        streamRepository: StreamRepository,
        cachePort: CachePort,
    ): DeleteStreamUseCase = DeleteStreamUseCase(streamRepository, cachePort)

    @Bean
    fun videoResponseMapper(fileStoragePort: FileStoragePort): VideoResponseMapper =
        VideoResponseMapper(fileStoragePort)
}
