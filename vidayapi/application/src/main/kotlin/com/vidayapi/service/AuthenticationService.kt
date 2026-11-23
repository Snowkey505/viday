package com.vidayapi.service

import com.vidayapi.model.AccountLockState
import com.vidayapi.model.AuthAccount
import com.vidayapi.model.AuthChallenge
import com.vidayapi.model.AuthChallengePurpose
import com.vidayapi.model.Error
import com.vidayapi.model.Result
import com.vidayapi.model.User
import com.vidayapi.model.flatMap
import com.vidayapi.model.left
import com.vidayapi.model.right
import com.vidayapi.port.AuthRepository
import com.vidayapi.port.EmailSenderPort
import com.vidayapi.port.PasswordEncoder
import com.vidayapi.port.UserRepository
import com.vidayapi.port.VerificationEmail
import com.vidayapi.usecase.RegisterUserUseCase
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Аутентификация ЛР5 поверх BDD-сценариев:
 *  * пароль + второй фактор по email;
 *  * ограниченное число попыток и временная блокировка;
 *  * восстановление доступа по коду из email после блокировки;
 *  * плановая смена пароля (ротация по password_changed_at).
 *
 * Старый AuthService оставлен без изменений для уже существующих сценариев
 * ЛР2/ЛР4. Пользователи без записи user_auth входят по одному паролю.
 */
@Service
class AuthenticationService(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val emailSender: EmailSenderPort,
    private val jwtService: JwtService,
    private val registerUserUseCase: RegisterUserUseCase,
    private val passwordEncoder: PasswordEncoder,
    @Value("\${viday.auth.max-login-attempts:5}") private val maxLoginAttempts: Int,
    @Value("\${viday.auth.lock-duration-minutes:15}") private val lockDurationMinutes: Long,
    @Value("\${viday.auth.password-rotation-days:90}") private val passwordRotationDays: Long,
    @Value("\${viday.auth.two-factor-code-ttl-minutes:5}") private val codeTtlMinutes: Long,
    @Value("\${viday.auth.two-factor-max-attempts:5}") private val maxCodeAttempts: Int,
) {
    private val secureRandom = SecureRandom()

    /**
     * Регистрация с необязательным email. Если email передан, создаётся
     * запись user_auth (2FA + ротация + лимит попыток). При ошибке создания
     * учётных данных пользователь откатывается, чтобы не оставлять сироту.
     */
    fun register(username: String, password: String, email: String?): Result<User> {
        val normalizedEmail = email?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedEmail != null && !EMAIL_REGEX.matches(normalizedEmail)) {
            return Error.ValidationFailed("Invalid email").left()
        }
        if (normalizedEmail != null) {
            val taken = authRepository.isEmailTaken(normalizedEmail)
            if (taken.isFailure) return Error.StorageFailure.left()
            if (taken.getOrNull() == true) return Error.AlreadyExists.left()
        }

        return registerUserUseCase.execute(username, password).flatMap { user ->
            if (normalizedEmail == null) {
                user.right()
            } else {
                val userId = user.id ?: return@flatMap Error.StorageFailure.left()
                val provisioned = authRepository.createAccount(userId, normalizedEmail, OffsetDateTime.now())
                if (provisioned.isFailure) {
                    // Регистрация идёт под ролью viday_auth, у которой есть DELETE
                    // на viday."user": откатываем частично созданного пользователя.
                    userRepository.deleteById(userId)
                    Result.failure(provisioned.exceptionOrNull() ?: Error.StorageFailure)
                } else {
                    user.right()
                }
            }
        }
    }

    fun login(username: String, password: String): Result<LoginOutcome> {
        val now = OffsetDateTime.now()
        val accountResult = authRepository.findAccountByUsername(username)
        if (accountResult.isFailure) return Error.StorageFailure.left()
        val account = accountResult.getOrNull() ?: return legacyLogin(username, password)
        return authenticate(account, password, now)
    }

    private fun legacyLogin(username: String, password: String): Result<LoginOutcome> {
        val userResult = userRepository.findByUsername(username)
        if (userResult.isFailure) return Error.StorageFailure.left()
        val user = userResult.getOrNull() ?: return LoginOutcome.InvalidCredentials(maxLoginAttempts).right()
        if (!passwordEncoder.matches(password, user.passwordHash)) {
            return LoginOutcome.InvalidCredentials(maxLoginAttempts).right()
        }
        val userId = user.id ?: return Error.StorageFailure.left()
        return LoginOutcome.Authenticated(
            jwtService.generateToken(userId, user.username, user.role),
        ).right()
    }

    private fun authenticate(
        account: AuthAccount,
        password: String,
        now: OffsetDateTime,
    ): Result<LoginOutcome> {
        val lockedUntil = account.lockedUntil
        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            return LoginOutcome.Locked(lockedUntil).right()
        }
        if (lockedUntil != null) {
            val reset = authRepository.resetLoginAttempts(account.userId)
            if (reset.isFailure) return Error.StorageFailure.left()
        }

        if (!passwordEncoder.matches(password, account.passwordHash)) {
            val stateResult = authRepository.recordFailedLogin(
                userId = account.userId,
                maxAttempts = maxLoginAttempts,
                lockDuration = Duration.ofMinutes(lockDurationMinutes),
                now = now,
            )
            if (stateResult.isFailure) return Error.StorageFailure.left()
            val state = stateResult.getOrNull() ?: return Error.StorageFailure.left()
            return failedPasswordOutcome(state).right()
        }

        val reset = authRepository.resetLoginAttempts(account.userId)
        if (reset.isFailure) return Error.StorageFailure.left()

        if (account.twoFactorEnabled && account.email.isNotBlank()) {
            val challengeResult = createChallenge(account, AuthChallengePurpose.LOGIN_2FA, now)
            if (challengeResult.isFailure) return Error.StorageFailure.left()
            val challenge = challengeResult.getOrNull() ?: return Error.StorageFailure.left()
            return LoginOutcome.TwoFactorRequired(
                challengeId = challenge.id.toString(),
                channel = "EMAIL",
                emailMasked = maskEmail(account.email),
            ).right()
        }

        return afterPassword(account, now).right()
    }

    private fun failedPasswordOutcome(state: AccountLockState): LoginOutcome =
        if (state.isLocked) {
            LoginOutcome.Locked(state.lockedUntil!!)
        } else {
            LoginOutcome.InvalidCredentials(
                attemptsRemaining = (maxLoginAttempts - state.failedAttempts).coerceAtLeast(0),
            )
        }

    fun verifyTwoFactor(challengeId: String, code: String): Result<TwoFactorOutcome> {
        val id = runCatching { UUID.fromString(challengeId) }.getOrNull()
            ?: return TwoFactorOutcome.ChallengeExpired("Invalid challenge id").right()
        val now = OffsetDateTime.now()
        val challengeResult = authRepository.findChallenge(id)
        if (challengeResult.isFailure) return Error.StorageFailure.left()
        val challenge = challengeResult.getOrNull()
            ?: return TwoFactorOutcome.ChallengeExpired("Challenge not found").right()

        if (challenge.purpose != AuthChallengePurpose.LOGIN_2FA ||
            challenge.consumedAt != null ||
            !challenge.expiresAt.isAfter(now)
        ) {
            return TwoFactorOutcome.ChallengeExpired("Challenge is expired or already used").right()
        }
        if (challenge.attempts >= challenge.maxAttempts) {
            return TwoFactorOutcome.ChallengeExpired("Too many code attempts").right()
        }

        if (!passwordEncoder.matches(code, challenge.codeHash)) {
            val incremented = authRepository.incrementChallengeAttempts(id)
            if (incremented.isFailure) return Error.StorageFailure.left()
            val attempts = incremented.getOrNull() ?: (challenge.attempts + 1)
            if (attempts >= challenge.maxAttempts) {
                authRepository.consumeChallenge(id, now)
                return TwoFactorOutcome.ChallengeExpired("Too many code attempts").right()
            }
            return TwoFactorOutcome.InvalidCode(
                attemptsRemaining = (challenge.maxAttempts - attempts).coerceAtLeast(0),
            ).right()
        }

        val consumed = authRepository.consumeChallenge(id, now)
        if (consumed.isFailure) return Error.StorageFailure.left()
        val accountResult = authRepository.findAccountByUserId(challenge.userId)
        if (accountResult.isFailure) return Error.StorageFailure.left()
        val account = accountResult.getOrNull() ?: return Error.NotFound.left()
        return when (val outcome = afterPassword(account, now)) {
            is LoginOutcome.Authenticated -> TwoFactorOutcome.Authenticated(outcome.token).right()
            is LoginOutcome.PasswordChangeRequired ->
                TwoFactorOutcome.PasswordChangeRequired(outcome.passwordChangeToken).right()
            else -> Error.StorageFailure.left()
        }
    }

    fun changePassword(passwordChangeToken: String, newPassword: String): Result<Unit> {
        val userId = jwtService.validatePasswordChangeToken(passwordChangeToken).getOrNull()
            ?: return Error.Unauthorized.left()
        validatePassword(newPassword)?.let { return it.left() }

        val accountResult = authRepository.findAccountByUserId(userId)
        if (accountResult.isFailure) return Error.StorageFailure.left()
        if (accountResult.getOrNull() == null) return Error.NotFound.left()

        return authRepository.updatePassword(
            userId = userId,
            passwordHash = passwordEncoder.encode(newPassword),
            passwordChangedAt = OffsetDateTime.now(),
        )
    }

    /**
     * Запрос восстановления. Ответ всегда одинаков, чтобы не раскрывать
     * существование аккаунта. Код создаётся только для email-аккаунта.
     */
    fun requestRecovery(username: String): Result<Unit> {
        val accountResult = authRepository.findAccountByUsername(username)
        if (accountResult.isFailure) return Error.StorageFailure.left()
        val account = accountResult.getOrNull() ?: return Unit.right()
        if (!account.twoFactorEnabled || account.email.isBlank()) return Unit.right()

        val challengeResult = createChallenge(account, AuthChallengePurpose.PASSWORD_RECOVERY, OffsetDateTime.now())
        if (challengeResult.isFailure) return Error.StorageFailure.left()
        return Unit.right()
    }

    /**
     * Подтверждение восстановления: проверяет код, снимает блокировку,
     * задаёт новый пароль и сбрасывает счётчик попыток.
     */
    fun confirmRecovery(username: String, code: String, newPassword: String): Result<Unit> {
        val accountResult = authRepository.findAccountByUsername(username)
        if (accountResult.isFailure) return Error.StorageFailure.left()
        val account = accountResult.getOrNull() ?: return Error.Unauthorized.left()

        val challengeResult = authRepository.findLatestChallenge(account.userId, AuthChallengePurpose.PASSWORD_RECOVERY)
        if (challengeResult.isFailure) return Error.StorageFailure.left()
        val challenge = challengeResult.getOrNull() ?: return Error.Unauthorized.left()

        val now = OffsetDateTime.now()
        if (!challenge.expiresAt.isAfter(now) || challenge.attempts >= challenge.maxAttempts) {
            return Error.Unauthorized.left()
        }
        if (!passwordEncoder.matches(code, challenge.codeHash)) {
            val incremented = authRepository.incrementChallengeAttempts(challenge.id)
            if (incremented.isFailure) return Error.StorageFailure.left()
            if ((incremented.getOrNull() ?: 0) >= challenge.maxAttempts) {
                authRepository.consumeChallenge(challenge.id, now)
            }
            return Error.Unauthorized.left()
        }

        validatePassword(newPassword)?.let { return it.left() }

        val updated = authRepository.updatePassword(
            userId = account.userId,
            passwordHash = passwordEncoder.encode(newPassword),
            passwordChangedAt = now,
        )
        if (updated.isFailure) return Error.StorageFailure.left()
        authRepository.consumeChallenge(challenge.id, now)
        authRepository.resetLoginAttempts(account.userId)
        return Unit.right()
    }

    private fun createChallenge(
        account: AuthAccount,
        purpose: AuthChallengePurpose,
        now: OffsetDateTime,
    ): Result<AuthChallenge> {
        val code = generateCode()
        val challenge = AuthChallenge(
            id = UUID.randomUUID(),
            userId = account.userId,
            purpose = purpose,
            codeHash = passwordEncoder.encode(code),
            attempts = 0,
            maxAttempts = maxCodeAttempts,
            expiresAt = now.plusMinutes(codeTtlMinutes),
        )
        val saved = authRepository.createChallenge(challenge)
        if (saved.isFailure) return Error.StorageFailure.left()

        val sent = emailSender.sendVerificationCode(
            VerificationEmail(
                recipient = account.email,
                username = account.username,
                code = code,
                purpose = purpose,
                challengeId = challenge.id,
            ),
        )
        return if (sent.isFailure) Error.StorageFailure.left() else challenge.right()
    }

    private fun afterPassword(account: AuthAccount, now: OffsetDateTime): LoginOutcome =
        if (isPasswordExpired(account, now)) {
            LoginOutcome.PasswordChangeRequired(
                jwtService.generatePasswordChangeToken(account.userId, account.username),
            )
        } else {
            LoginOutcome.Authenticated(
                jwtService.generateToken(account.userId, account.username, account.role),
            )
        }

    private fun isPasswordExpired(account: AuthAccount, now: OffsetDateTime): Boolean =
        account.passwordChangedAt.plusDays(passwordRotationDays).isBefore(now)

    private fun validatePassword(password: String): Error? = when {
        password.length < 8 -> Error.ValidationFailed("Password must be at least 8 characters")
        !password.any { it.isLetter() } || !password.any { it.isDigit() } ->
            Error.ValidationFailed("Password must contain at least one letter and one digit")
        else -> null
    }

    private fun generateCode(): String = "%06d".format(secureRandom.nextInt(1_000_000))

    private fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at <= 0) return "***"
        return "${email.first()}***${email.substring(at)}"
    }

    private companion object {
        val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
