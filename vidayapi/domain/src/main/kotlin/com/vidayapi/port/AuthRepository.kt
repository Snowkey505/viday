package com.vidayapi.port

import com.vidayapi.model.AuthAccount
import com.vidayapi.model.AuthChallenge
import com.vidayapi.model.AuthChallengePurpose
import com.vidayapi.model.AccountLockState
import com.vidayapi.model.Result
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Хранилище учётных данных ЛР5.
 *
 * Отдельный порт (а не расширение [UserRepository]) выбран осознанно:
 * старые тесты и in-memory реализации профиля не должны знать о 2FA/блокировках.
 */
interface AuthRepository {
    fun findAccountByUsername(username: String): Result<AuthAccount?>
    fun findAccountByUserId(userId: Int): Result<AuthAccount?>
    fun isEmailTaken(email: String): Result<Boolean>

    fun createAccount(userId: Int, email: String, passwordChangedAt: OffsetDateTime): Result<Unit>
    fun updatePassword(userId: Int, passwordHash: String, passwordChangedAt: OffsetDateTime): Result<Unit>

    fun recordFailedLogin(
        userId: Int,
        maxAttempts: Int,
        lockDuration: Duration,
        now: OffsetDateTime,
    ): Result<AccountLockState>

    fun resetLoginAttempts(userId: Int): Result<Unit>

    fun createChallenge(challenge: AuthChallenge): Result<Unit>
    fun findChallenge(id: UUID): Result<AuthChallenge?>
    fun findLatestChallenge(userId: Int, purpose: AuthChallengePurpose): Result<AuthChallenge?>
    fun incrementChallengeAttempts(id: UUID): Result<Int>
    fun consumeChallenge(id: UUID, consumedAt: OffsetDateTime): Result<Unit>
}
