package com.snowkey.viday.presentation.videosScreen

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.snowkey.viday.R
import com.snowkey.viday.model.Navigation
import com.snowkey.viday.model.VideoView
import com.snowkey.viday.presentation.videosScreen.components.AddToPlaylistDialog
import com.snowkey.viday.presentation.videosScreen.components.VideoItem
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideosScreen(
    vm: VideosViewModel = koinViewModel(),
    navController: NavController
) {
    val state by vm.uiState.collectAsState()
    val videos by vm.videos.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val playlistInfo by vm.playlistInfo.collectAsState()

    val userId by vm.userId.collectAsState()
    val playlistId by vm.playlistId.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val actionLabelText = stringResource(R.string.repeat)
    var addToPlaylistVideoId by remember { mutableStateOf<Long?>(null) }

    val errorState = state as? VideosUiState.Error
    val errorMessage = errorState?.let { stringResource(it.messageResId) }

    LaunchedEffect(Unit) {
        vm.load()
    }

    LaunchedEffect(errorState, errorMessage) {
        if (errorState != null && errorMessage != null) {
            val result = snackbarHostState.showSnackbar(
                message = errorMessage,
                actionLabel = actionLabelText,
                duration = SnackbarDuration.Short
            )

            when (result) {
                SnackbarResult.ActionPerformed -> {
                    errorState.onRepeat.invoke()
                }

                SnackbarResult.Dismissed -> {}
            }
        }
    }

    LaunchedEffect(vm) {
        vm.events.collect { messageId ->
            snackbarHostState.showSnackbar(
                message = navController.context.getString(messageId),
                duration = SnackbarDuration.Short,
            )
        }
    }
    Log.d("PLAYLIST", "owner = ${playlistInfo}, userId = $userId")

    DisposableEffect(Unit) {
        onDispose {
            vm.clear()
        }
    }

    addToPlaylistVideoId?.let { videoId ->
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { addToPlaylistVideoId = null },
            onAdd = { playlistId ->
                vm.addVideoToPlaylist(videoId = videoId, playlistId = playlistId)
                addToPlaylistVideoId = null
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            if (playlistInfo != null) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = playlistInfo!!.name,
                                color = MaterialTheme.colorScheme.onBackground,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState
            ) { snackbarData ->
                Snackbar(
                    snackbarData = snackbarData,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    actionColor = MaterialTheme.colorScheme.primary
                )
            }
        }
    ) { contentPadding ->
        Box(modifier = Modifier.padding(contentPadding)) {
            VideosScreenContent(
                state = state,
                videos = videos,
                playlistId = if (playlistInfo != null && playlistInfo!!.ownerId == userId) playlistInfo!!.id else null,
                onAddToPlaylist = { videoId -> addToPlaylistVideoId = videoId },
                onRemoveFromPlaylist = { videoId -> vm.removeVideoFromPlaylist(videoId) },
                onVideoClick = { videoId ->
                    navController.navigate(
                        Navigation.createVideoRoute(
                            userId = userId,
                            playlistId = playlistId,
                            videoId = videoId
                        )
                    )
                },
                goBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun VideosScreenContent(
    state: VideosUiState,
    videos: List<VideoView>,
    playlistId: Long? = null,
    onAddToPlaylist: (Long) -> Unit = {},
    onSearch: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onVideoClick: (Long) -> Unit = {},
    onRemoveFromPlaylist: (Long) -> Unit = { },
    goBack: () -> Unit = {}
) {
    when (state) {
        is VideosUiState.Loading -> {
            VideosLoading()
        }

        else -> {
            Videos(
                videos = videos,
                onAddToPlaylist = onAddToPlaylist,
                playlistId = playlistId,
                onVideoClick = onVideoClick,
                onRemoveFromPlaylist = onRemoveFromPlaylist
            )
        }
    }
}

@Composable
fun Videos(
    videos: List<VideoView>,
    playlistId: Long? = null,
    onRemoveFromPlaylist: (Long) -> Unit = { _ -> Unit },
    onVideoClick: (Long) -> Unit = {},
    onAddToPlaylist: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = videos,
            key = { video -> video.id }
        ) { video ->
            VideoItem(
                video = video,
                onAddToPlaylist = onAddToPlaylist,
                playlistId = playlistId,
                onVideoClick = onVideoClick,
                onRemoveFromPlaylist = onRemoveFromPlaylist
            )
        }
    }
}

@Composable
fun VideosError(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = message)
    }
}

@Composable
fun VideosLoading() {
    CircularProgressIndicator()
}
