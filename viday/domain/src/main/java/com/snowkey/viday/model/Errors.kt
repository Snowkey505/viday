package com.snowkey.viday.model

enum class VideoError {
    OK,
    SERVER_ERROR,
    NETWORK_ERROR,
    ACCESS_ERROR,
    UNKNOWN_ERROR
}

enum class AuthError {
    OK,
    INVALID_CREDENTIALS,
    NETWORK_ERROR,
    SERVER_ERROR,
    UNKNOWN_ERROR
}

enum class PlaylistError {
    OK,
    ACCESS_ERROR,
    FORBIDDEN,
    NETWORK_ERROR,
    SERVER_ERROR,
    NOT_FOUND,
    CONTENT_ALREADY_EXISTS,
    UNKNOWN_ERROR
}

enum class UserError {
    OK,
    ACCESS_ERROR,
    NETWORK_ERROR,
    SERVER_ERROR,
    NOT_FOUND,
    ALREADY_FOLLOWING,
    ALREADY_CREATOR,
    UNKNOWN_ERROR
}
