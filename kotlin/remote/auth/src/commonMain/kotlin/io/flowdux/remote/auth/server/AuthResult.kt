package io.flowdux.remote.auth.server

/**
 * Outcome of an authentication attempt.
 *
 * @param P The principal type on success.
 */
sealed interface AuthResult<out P : AuthPrincipal> {
    /** Authentication succeeded with a verified [principal]. */
    data class Success<P : AuthPrincipal>(val principal: P) : AuthResult<P>

    /** Authentication failed with a [reason] message. */
    data class Failure(val reason: String) : AuthResult<Nothing>
}

/**
 * Returns the [principal][AuthResult.Success.principal] on success,
 * or invokes [onFailure] with the reason on failure.
 *
 * Designed for fail-fast early-return patterns:
 * ```kotlin
 * val principal = authed.awaitAuth(scope).getOrElse { reason ->
 *     close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason))
 *     return@webSocket
 * }
 * // principal is guaranteed to be non-null here
 * ```
 */
inline fun <P : AuthPrincipal> AuthResult<P>.getOrElse(onFailure: (reason: String) -> Nothing): P = when (this) {
    is AuthResult.Success -> principal
    is AuthResult.Failure -> onFailure(reason)
}
