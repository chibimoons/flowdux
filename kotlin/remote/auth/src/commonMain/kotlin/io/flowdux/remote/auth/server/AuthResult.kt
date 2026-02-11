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
