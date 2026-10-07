package com.snowkey.viday.di

import com.snowkey.viday.usecase.playlist.CreatePlaylistUseCase
import com.snowkey.viday.usecase.video.GetVideosUseCase
import com.snowkey.viday.usecase.auth.LoginUseCase
import com.snowkey.viday.usecase.auth.LogoutUseCase
import com.snowkey.viday.usecase.auth.SignupUseCase
import com.snowkey.viday.usecase.user.FollowCreatorUseCase
import com.snowkey.viday.usecase.user.UnfollowCreatorUseCase
import com.snowkey.viday.usecase.playlist.AddContentToPlaylistUseCase
import com.snowkey.viday.usecase.playlist.DeletePlaylistUseCase
import com.snowkey.viday.usecase.playlist.GetAvailablePlaylistsUseCase
import com.snowkey.viday.usecase.playlist.GetPlaylistByIdUseCase
import com.snowkey.viday.usecase.playlist.GetPlaylistsUseCase
import com.snowkey.viday.usecase.playlist.RemoveContentFromPlaylistUseCase
import com.snowkey.viday.usecase.playlist.UpdatePlaylistUseCase
import com.snowkey.viday.usecase.user.GetUserByIdUseCase
import com.snowkey.viday.usecase.video.GetVideoByIdUseCase
import com.snowkey.viday.usecase.video.UploadVideoUseCase
import org.koin.dsl.module

val domainModule = module {
    factory { LoginUseCase(get()) }
    factory { SignupUseCase(get()) }
    factory { LogoutUseCase(get()) }

    factory { GetVideosUseCase(get()) }
    factory { GetVideoByIdUseCase(get()) }
    factory { UploadVideoUseCase(get()) }

    factory { CreatePlaylistUseCase(get()) }
    factory { GetPlaylistsUseCase(get()) }
    factory { GetAvailablePlaylistsUseCase(get()) }
    factory { GetPlaylistByIdUseCase(get()) }
    factory { UpdatePlaylistUseCase(get()) }
    factory { DeletePlaylistUseCase(get()) }
    factory { AddContentToPlaylistUseCase(get()) }
    factory { RemoveContentFromPlaylistUseCase(get()) }

    factory { FollowCreatorUseCase(get()) }
    factory { UnfollowCreatorUseCase(get()) }
    factory { GetUserByIdUseCase(get()) }
}
