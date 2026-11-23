package com.vidayapi.port

import com.vidayapi.model.AuthChallengePurpose
import com.vidayapi.model.Result
import java.util.UUID

/**
 * Письмо с одноразовым кодом. Содержит открытый код только на время отправки:
 * в БД он попадает лишь как BCrypt-хеш (в auth_challenge).
 */
data class VerificationEmail(
    val recipient: String,
    val username: String,
    val code: String,
    val purpose: AuthChallengePurpose,
    val challengeId: UUID,
)

interface EmailSenderPort {
    fun sendVerificationCode(message: VerificationEmail): Result<Unit>
}
