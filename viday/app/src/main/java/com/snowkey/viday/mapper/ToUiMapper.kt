package com.snowkey.viday.mapper

import com.snowkey.viday.model.Playlist
import com.snowkey.viday.model.PlaylistView
import com.snowkey.viday.model.Video
import com.snowkey.viday.model.VideoView

fun Playlist.toPlaylistView() = PlaylistView(
    id = id,
    name = name,
    accessType = accessType,
    ownerId = ownerId,
    previewUrl = null,
)

fun Video.toUi() = VideoView(
    id = id,
    source = source,
    name = name,
    description = description,
    preview = preview,
    ownerId = ownerId
)
