package com.snowkey.viday.presentation.playlistScreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.snowkey.viday.R
import com.snowkey.viday.model.Navigation
import com.snowkey.viday.model.PlaylistView
import com.snowkey.viday.presentation.playlistScreen.component.CreatePlaylistDialog
import com.snowkey.viday.presentation.playlistScreen.component.PlaylistItem
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    vm: PlaylistViewModel = koinViewModel(),
    navController: NavController,
) {
    val state by vm.uiState.collectAsState()
    val playlists by vm.playlists.collectAsState()

    val userId by vm.userId.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val actionLabelText = stringResource(R.string.repeat)
    val context = LocalContext.current

    var createOpen by remember { mutableStateOf(false) }
    var subscribeOpen by remember { mutableStateOf(false) }
    var playlistPendingDelete by remember { mutableStateOf<Long?>(null) }

    val errorState = state as? PlaylistUiState.Error
    val errorMessage = errorState?.let { stringResource(it.messageResId) }

    LaunchedEffect(errorState, errorMessage) {
        if (errorState != null && errorMessage != null) {
            val result =
                snackbarHostState.showSnackbar(
                    message = errorMessage,
                    actionLabel = actionLabelText,
                    duration = SnackbarDuration.Short,
                )
            when (result) {
                SnackbarResult.ActionPerformed -> errorState.onRepeat.invoke()
                SnackbarResult.Dismissed -> Unit
            }
        }
    }

    LaunchedEffect(vm) {
        vm.events.collect { ev ->
            when (ev) {
                is PlaylistUiEvent.Message ->
                    snackbarHostState.showSnackbar(
                        message = context.getString(ev.messageResId),
                        duration = SnackbarDuration.Long,
                    )
            }
        }
    }

    if (createOpen) {
        CreatePlaylistDialog(
            onDismiss = { createOpen = false },
            onConfirm = { name, access ->
                vm.createPlaylist(name, access)
                createOpen = false
            },
        )
    }

    playlistPendingDelete?.let { pid ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.background,
            onDismissRequest = { playlistPendingDelete = null },
            title = {
                Text(
                    text = stringResource(R.string.delete_playlist),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deletePlaylist(pid)
                        playlistPendingDelete = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.dialog_confirm),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistPendingDelete = null }) {
                    Text(
                        text = stringResource(R.string.dialog_cancel),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            text = {
                Text(
                    text = "${stringResource(R.string.dialog_confirm)}?",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyLarge
                )
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.playlists_screen_title),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.displayMedium
                    )
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { snackbarData ->
                Snackbar(
                    snackbarData = snackbarData,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    actionColor = MaterialTheme.colorScheme.primary,
                )
            }
        },
        floatingActionButton = {
            if (state is PlaylistUiState.Done) {
                FloatingActionButton(
                    onClick = { createOpen = true },
                    containerColor = MaterialTheme.colorScheme.background,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp,
                        focusedElevation = 0.dp,
                        hoveredElevation = 0.dp
                    )
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.create_playlist)
                    )
                }
            }
        },
    ) { contentPadding ->
        Box(modifier = Modifier.padding(contentPadding)) {
            PlaylistScreenContent(
                state = state,
                playlists = playlists,
                onPlaylistMenuDelete = { playlistPendingDelete = it },
                onPlaylistClick = { playlist ->
                    navController.navigate(
                        Navigation.createVideosRoute(
                            userId = userId,
                            playlistId = playlist.id,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
fun PlaylistScreenContent(
    state: PlaylistUiState,
    playlists: List<PlaylistView>,
    onPlaylistMenuDelete: (Long) -> Unit,
    onPlaylistClick: (PlaylistView) -> Unit,
) {
    when (state) {
        PlaylistUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is PlaylistUiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = stringResource(state.messageResId))
            }
        }

        PlaylistUiState.Done -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(
                    items = playlists,
                    key = { p -> p.id },
                ) { playlist ->
                    PlaylistItem(
                        playlist = playlist,
                        onDelete = onPlaylistMenuDelete,
                        onClick = onPlaylistClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscribeCreatorDialog(
    onDismiss: () -> Unit,
    onFollow: (Long) -> Unit,
    onUnfollow: (Long) -> Unit,
) {
    var userIdRaw by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subscribe_to_creator_title)) },
        text = {
            Column {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = userIdRaw,
                    onValueChange = { userIdRaw = it },
                    label = { Text(stringResource(R.string.creator_user_id_hint)) },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            userIdRaw.toLongOrNull()?.let(onFollow)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.subscribe_action))
                    }
                    Button(
                        onClick = {
                            userIdRaw.toLongOrNull()?.let(onUnfollow)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.unsubscribe_action))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun ContentIdDialog(
    titleRes: Int,
    showPositionField: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (contentId: Long, position: Int?) -> Unit,
) {
    var contentRaw by remember { mutableStateOf("") }
    var positionRaw by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = contentRaw,
                    onValueChange = { contentRaw = it },
                    label = { Text(stringResource(R.string.content_id_hint)) },
                    singleLine = true,
                )
                if (showPositionField) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = positionRaw,
                        onValueChange = { positionRaw = it },
                        label = { Text(stringResource(R.string.position_hint)) },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cid = contentRaw.toLongOrNull() ?: return@TextButton
                    val position =
                        if (showPositionField) {
                            positionRaw.toIntOrNull() ?: return@TextButton
                        } else {
                            null
                        }
                    onConfirm(cid, position)
                },
            ) {
                Text(stringResource(R.string.dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}
