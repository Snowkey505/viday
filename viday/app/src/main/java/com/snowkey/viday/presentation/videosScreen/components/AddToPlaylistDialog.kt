package com.snowkey.viday.presentation.videosScreen.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.snowkey.viday.R
import com.snowkey.viday.model.PlaylistView

@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistView>,
    onDismiss: () -> Unit,
    onAdd: (Long) -> Unit,
) {
    var selectedPlaylistId by remember(playlists) {
        mutableStateOf(playlists.firstOrNull()?.id)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        title = {
            Text(
                text = stringResource(R.string.add_to_playlist),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                playlists.forEach { playlist ->
                    TextButton(
                        onClick = { selectedPlaylistId = playlist.id },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (selectedPlaylistId == playlist.id) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                MaterialTheme.colorScheme.background
                            },
                            contentColor = if (selectedPlaylistId == playlist.id) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onBackground
                            }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = playlist.name,
                            color = if (selectedPlaylistId == playlist.id) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onBackground
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (playlists.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_playlists_available),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedPlaylistId?.let(onAdd)
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                enabled = selectedPlaylistId != null,
            ) {
                Text(
                    text = stringResource(R.string.add)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {

                Text(
                    text = stringResource(R.string.dialog_cancel)
                )
            }
        }
    )
}
