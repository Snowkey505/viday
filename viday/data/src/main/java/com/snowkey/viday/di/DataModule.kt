package com.snowkey.viday.di

import com.snowkey.viday.repository.AuthRepository
import com.snowkey.viday.repository.AuthRepositoryImpl
import com.snowkey.viday.repository.PlaylistRepository
import com.snowkey.viday.repository.PlaylistRepositoryImpl
import com.snowkey.viday.repository.UserRepository
import com.snowkey.viday.repository.UserRepositoryImpl
import com.snowkey.viday.repository.VideoRepository
import com.snowkey.viday.repository.VideoRepositoryImpl
import org.koin.dsl.module

val dataModule = module {
    includes(networkModule)

    single<VideoRepository> {
        VideoRepositoryImpl(
            apiService = get(),
            tokenManager = get()
        )
    }

    single<PlaylistRepository> {
        PlaylistRepositoryImpl(
            apiService = get(),
            tokenManager = get()
        )
    }

    single<UserRepository> {
        UserRepositoryImpl(
            apiService = get(),
            tokenManager = get()
        )
    }

    single<AuthRepository> {
        AuthRepositoryImpl(
            apiService = get(),
            tokenManager = get()
        )
    }
}
