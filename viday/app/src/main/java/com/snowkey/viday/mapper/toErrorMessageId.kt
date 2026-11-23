package com.snowkey.viday.mapper

import com.snowkey.viday.R
import com.snowkey.viday.model.AuthError
import com.snowkey.viday.model.PlaylistError
import com.snowkey.viday.model.UserError
import com.snowkey.viday.model.VideoError

fun AuthError.toErrorMessageId(): Int {
    return when (this) {
        AuthError.OK -> R.string.success
        AuthError.INVALID_CREDENTIALS -> R.string.access_error
        AuthError.NETWORK_ERROR -> R.string.network_error
        AuthError.SERVER_ERROR -> R.string.server_error
        AuthError.UNKNOWN_ERROR -> R.string.unknown_error
    }
}

fun VideoError.toErrorMessageId(): Int =
    when (this) {
        VideoError.SERVER_ERROR -> R.string.server_error
        VideoError.ACCESS_ERROR -> R.string.access_error
        VideoError.NETWORK_ERROR -> R.string.network_error
        else -> R.string.unknown_error
    }

fun PlaylistError.toErrorMessageId(): Int =
    when (this) {
        PlaylistError.OK -> R.string.success
        PlaylistError.ACCESS_ERROR -> R.string.access_error
        PlaylistError.FORBIDDEN -> R.string.playlist_forbidden_error
        PlaylistError.NOT_FOUND -> R.string.unknown_error
        PlaylistError.CONTENT_ALREADY_EXISTS -> R.string.playlist_conflict_error
        PlaylistError.NETWORK_ERROR -> R.string.network_error
        PlaylistError.SERVER_ERROR -> R.string.server_error
        PlaylistError.UNKNOWN_ERROR -> R.string.unknown_error
    }

fun UserError.toErrorMessageId(): Int =
    when (this) {
        UserError.OK -> R.string.success
        UserError.ACCESS_ERROR -> R.string.access_error
        UserError.ALREADY_FOLLOWING -> R.string.already_following_error
        UserError.NOT_FOUND -> R.string.unknown_error
        UserError.NETWORK_ERROR -> R.string.network_error
        UserError.SERVER_ERROR -> R.string.server_error
        UserError.UNKNOWN_ERROR -> R.string.unknown_error
        UserError.ALREADY_CREATOR -> R.string.unknown_error
    }
