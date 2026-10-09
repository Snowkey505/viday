@file:Suppress("OPT_IN_ARGUMENT_IS_NOT_MARKER")

package com.snowkey.viday.presentation.videoScreen

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.snowkey.viday.R
import com.snowkey.viday.model.VideoView
import com.snowkey.viday.presentation.videoScreen.components.VideoInfo
import com.snowkey.viday.presentation.videoScreen.components.VideoPlayer
import com.snowkey.viday.presentation.videosScreen.components.AddToPlaylistDialog
import org.koin.androidx.compose.koinViewModel

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun VideoScreen(
    vm: VideoViewModel = koinViewModel(),
    navController: NavController
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val video by vm.video.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val userId by vm.userId.collectAsStateWithLifecycle()
    val owner by vm.owner.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        vm.load()
    }

    LaunchedEffect(vm) {
        vm.events.collect { messageId ->
            snackbarHostState.showSnackbar(
                message = context.getString(messageId),
                duration = SnackbarDuration.Short,
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.clear()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    if (showAddToPlaylistDialog && video != null) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { showAddToPlaylistDialog = false },
            onAdd = { playlistId ->
                vm.addToPlaylist(playlistId, video!!.id)
                showAddToPlaylistDialog = false
            },
        )
    }

    if (isLandscape) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (state) {
                is VideoUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is VideoUiState.Error -> {
                    val errorState = state as VideoUiState.Error
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(errorState.messageResId),
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { errorState.onRepeat.invoke() }) {
                                Text(stringResource(R.string.repeat))
                            }
                        }
                    }
                }

                is VideoUiState.Done -> {
                    if (video != null) {
                        VideoPlayer(
                            videoUrl = video!!.source,
                            modifier = Modifier.fillMaxSize(),
                            isFullscreen = true,
                            onFullscreenToggle = {
                                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = video?.name ?: stringResource(R.string.video_player),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState)
            }
        ) { contentPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
            ) {
                when (state) {
                    is VideoUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    is VideoUiState.Error -> {
                        val errorState = state as VideoUiState.Error
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(errorState.messageResId),
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { errorState.onRepeat.invoke() }) {
                                    Text(stringResource(R.string.repeat))
                                }
                            }
                        }
                    }

                    is VideoUiState.Done -> {
                        if (video != null) {
                            VideoScreenContent(
                                video = video!!,
                                userId = userId,
                                ownerName = owner,
                                onAddToPlaylist = { showAddToPlaylistDialog = true },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoScreenContent(
    video: VideoView,
    userId: Long?,
    ownerName: String,
    onAddToPlaylist: () -> Unit,
    onBack: () -> Unit
) {
    var isFullscreen by remember { mutableStateOf(false) }
    val activity = LocalActivity.current

    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp)
    ) {
        item {
            VideoPlayer(
                videoUrl = video.source,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp),
                isFullscreen = false,
                onFullscreenToggle = { isFullscreen = true }
            )
        }

        item {
            VideoInfo(
                video = video,
                username = ownerName,
                onAddToPlaylist = onAddToPlaylist
            )
        }
    }
}
