package com.snowkey.viday.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.snowkey.viday.data.PreferencesLocalDataSource
import com.snowkey.viday.data.PreferencesLocalDataSourceImpl
import com.snowkey.viday.presentation.loginScreen.LoginViewModel
import com.snowkey.viday.presentation.playlistScreen.PlaylistViewModel
import com.snowkey.viday.presentation.profileScreen.ProfileViewModel
import com.snowkey.viday.presentation.signUpScreen.SignUpViewModel
import com.snowkey.viday.presentation.videoScreen.VideoViewModel
import com.snowkey.viday.presentation.videosScreen.VideosViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    viewModel<VideosViewModel> { params ->
        VideosViewModel(
            getVideosUseCase = get(),
            getPlaylistsUseCase = get(),
            addContentToPlaylistUseCase = get(),
            getPlaylistByIdUseCase = get(),
            removeContentFromPlaylistUseCase = get(),
            savedStateHandle = params.get()
        )
    }

    viewModel<VideoViewModel> { params ->
        VideoViewModel(
            savedStateHandle = params.get(),
            getVideoByIdUseCase = get(),
            getPlaylistsUseCase = get(),
            addContentToPlaylistUseCase = get(),
            getUserByIdUseCase = get(),
            removeContentFromPlaylistUseCase = get()
        )
    }

    viewModel<LoginViewModel> {
        LoginViewModel(
            loginUseCase = get(),
            prefsDataSource = get()
        )
    }

    viewModel<SignUpViewModel> {
        SignUpViewModel(
            signupUseCase = get()
        )
    }

    viewModel<PlaylistViewModel> { params ->
        PlaylistViewModel(
            savedStateHandle = params.get(),
            getPlaylistsUseCase = get(),
            getAvailablePlaylistsUseCase = get(),
            createPlaylistUseCase = get(),
            deletePlaylistUseCase = get(),
            addContentToPlaylistUseCase = get(),
            removeContentFromPlaylistUseCase = get(),
            followCreatorUseCase = get(),
            unfollowCreatorUseCase = get(),
            getUserByIdUseCase = get(),
            getVideosUseCase = get(),
        )
    }

    viewModel<ProfileViewModel> {
        ProfileViewModel(
            logoutUseCase = get(),
            prefsDataSource = get()
        )
    }

    single<DataStore<Preferences>>(qualifier = named("prefs")) {
        PreferenceDataStoreFactory.create(
            produceFile = {
                androidContext().preferencesDataStoreFile("app_prefs")
            }
        )
    }

    single<PreferencesLocalDataSource> {
        PreferencesLocalDataSourceImpl(get(named("prefs")))
    }
}
