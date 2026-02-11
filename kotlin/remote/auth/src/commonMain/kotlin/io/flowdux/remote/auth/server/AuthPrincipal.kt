package io.flowdux.remote.auth.server

/**
 * Marker interface for a verified authentication principal.
 * Users implement this with their domain-specific identity type.
 *
 * Example:
 * ```kotlin
 * data class UserPrincipal(
 *     val userId: String,
 *     val displayName: String,
 *     val roles: Set<String>,
 * ) : AuthPrincipal
 * ```
 */
interface AuthPrincipal
