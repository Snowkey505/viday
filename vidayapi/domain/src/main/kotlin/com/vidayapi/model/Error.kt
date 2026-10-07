package com.vidayapi.model

/**
 * Business error used as the failure channel of [Result].
 *
 * Extends [RuntimeException] so it can be carried by [kotlin.Result],
 * whose failure slot requires a [Throwable].
 */
sealed class Error : RuntimeException() {
    object NotFound : Error()
    object Unauthorized : Error()
    object Forbidden : Error()
    object AlreadyExists : Error()
    object InvalidInput : Error()
    object StorageFailure : Error()
    data class ValidationFailed(val details: String) : Error()
    object UserAlreadyFollowed : Error()
    object CannotFollowSelf : Error()
    object PlaylistPositionConflict : Error()
    object StreamKeyInvalid : Error()
    object StreamNotFound : Error()
    object StreamAlreadyLive : Error()
    object StreamNotLive : Error()
    object NotVideoOwner : Error()
    object AlreadyCreator : Error()
    object InvalidRoleTransition : Error()
    object CreatorRequired : Error()
}

/** Standard functional-style result: success value or [Error] failure. */
typealias Result<T> = kotlin.Result<T>

/** Wraps a value as a successful [Result]. */
fun <T> T.right(): Result<T> = Result.success(this)

/** Wraps a business [Error] as a failed [Result]. */
fun Error.left(): Result<Nothing> = Result.failure(this)

/** Chains a successful [Result] through [transform], propagating failures. */
inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> =
    fold(
        onSuccess = transform,
        onFailure = { Result.failure(it) }
    )

/** Maps the success value, propagating failures unchanged. */
inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> =
    fold(
        onSuccess = { Result.success(transform(it)) },
        onFailure = { Result.failure(it) }
    )

/**
 * Legacy-style fold with explicit failure/success lambdas, mirroring the old
 * `Either.fold(ifLeft = …, ifRight = …)` call sites.
 */
inline fun <T, R> Result<T>.fold(ifLeft: (Throwable) -> R, ifRight: (T) -> R): R =
    fold(onSuccess = ifRight, onFailure = ifLeft)
