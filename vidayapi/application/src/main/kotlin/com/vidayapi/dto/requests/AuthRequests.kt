package com.vidayapi.dto.requests

data class VerifyTwoFactorRequest(
    val challengeId: String,
    val code: String,
)

data class PasswordChangeRequest(
    val passwordChangeToken: String,
    val newPassword: String,
)

data class PasswordRecoveryRequest(
    val username: String,
)

data class PasswordRecoveryConfirmRequest(
    val username: String,
    val code: String,
    val newPassword: String,
)
