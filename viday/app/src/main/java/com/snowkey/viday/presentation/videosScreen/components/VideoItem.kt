package com.snowkey.viday.presentation.videosScreen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.snowkey.viday.R
import com.snowkey.viday.model.VideoView
import com.snowkey.viday.ui.theme.VidayTheme

@Composable
fun VideoItem(
    video: VideoView,
    playlistId: Long? = null,
    onVideoClick: (Long) -> Unit = {},
    onRemoveFromPlaylist: (Long) -> Unit = { _ -> Unit },
    onAddToPlaylist: (Long) -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = MaterialTheme.colorScheme.surface)
            .padding(8.dp)
            .clickable {
                onVideoClick(video.id)
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SubcomposeAsyncImage(
            model = video.preview,
            contentDescription = video.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(5.dp)),
            contentScale = ContentScale.Crop,
            loading = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp
                    )
                }
            },
            error = {
                Icon(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = "Error",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                    contentDescription = stringResource(R.string.account)
                )
                Text(
                    text = video.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Left,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onBackground,
                    overflow = TextOverflow.Ellipsis
                )
                Box(modifier = Modifier.size(24.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentSize(Alignment.TopEnd)
            ) {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onBackground,
                        contentDescription = stringResource(R.string.video_actions),
                    )
                }
                DropdownMenu(
                    containerColor = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(10.dp),
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    DropdownMenuItem(
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.onBackground
                        ),
                        text = {
                            Text(
                                text = stringResource(R.string.add_to_playlist),
                                color = MaterialTheme.colorScheme.onBackground,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onAddToPlaylist(video.id)
                        },
                    )
                    if (playlistId != null) {
                        DropdownMenuItem(
                            colors = MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.onBackground
                            ),
                            text = {
                                Text(
                                    text = stringResource(R.string.remove_from_playlist),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onRemoveFromPlaylist(video.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun VideoItemPreview() {
    VidayTheme(darkTheme = false) {
        VideoItem(
            VideoView(
                id = 1L,
                name = "Mando Diao - Get It On (Official Music Video)",
                preview = "https://is1-ssl.mzstatic.com/image/thumb/Video112/v4/b9/a3/c0/b9a3c0cb-dd30-d05c-2953-087b8295c7b0/cover.jpg/3000x2254mv.jpg",
                source = "",
                ownerId = 1L
            )
        )
    }
}
