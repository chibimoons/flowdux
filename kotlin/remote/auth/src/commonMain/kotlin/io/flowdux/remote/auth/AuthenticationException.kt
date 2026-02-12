package io.flowdux.remote.auth

/**
 * Thrown when authentication fails during the connection handshake.
 */
class AuthenticationException(message: String) : Exception(message)
