package io.flowdux.remote.auth

/**
 * Server-side token verifier that produces a principal on success.
 *
 * Users implement this for their auth method (JWT, API key, OAuth, etc.).
 *
 * Example (JWT):
 * ```kotlin
 * class JwtVerifier(private val secret: String) : AuthVerifier<UserPrincipal> {
 *     override suspend fun verify(token: String): AuthResult<UserPrincipal> {
 *         val decoded = JWT.decode(token, secret)
 *         return AuthResult.Success(UserPrincipal(decoded.userId, decoded.name, decoded.roles))
 *     }
 * }
 * ```
 */
fun interface AuthVerifier<P : AuthPrincipal> {
    suspend fun verify(token: String): AuthResult<P>
}
