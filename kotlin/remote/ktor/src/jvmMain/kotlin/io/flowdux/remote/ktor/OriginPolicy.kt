package io.flowdux.remote.ktor

/**
 * Policy for validating WebSocket `Origin` headers to prevent
 * Cross-Site WebSocket Hijacking (CSWSH) attacks.
 *
 * Usage:
 * ```kotlin
 * val policy = OriginPolicy.AllowList(
 *     origins = setOf("https://example.com", "https://app.example.com"),
 * )
 *
 * routing {
 *     webSocketWithOriginCheck("/ws", policy) {
 *         val conn = KtorWebSocketServerConnection(this)
 *         // ...
 *     }
 * }
 * ```
 *
 * @see webSocketWithOriginCheck
 */
sealed interface OriginPolicy {

    /**
     * Check whether the given [origin] is allowed by this policy.
     *
     * @param origin the value of the `Origin` header, or `null` if absent.
     * @return `true` if the connection should be accepted.
     */
    fun isAllowed(origin: String?): Boolean

    /**
     * Permits all origins, including absent `Origin` headers.
     * Useful for development or when origin validation is handled elsewhere.
     */
    data object AllowAll : OriginPolicy {
        override fun isAllowed(origin: String?): Boolean = true
    }

    /**
     * Permits only the specified [origins].
     *
     * Origins are compared case-insensitively after trimming trailing slashes.
     * Example allowed values: `"https://example.com"`, `"http://localhost:3000"`.
     *
     * @param origins set of allowed origin strings (scheme + host + optional port).
     * @param allowNullOrigin whether to allow requests with no `Origin` header
     *   (e.g. same-origin requests from some clients, or non-browser clients).
     *   Defaults to `false` for maximum security.
     */
    data class AllowList(val origins: Set<String>, val allowNullOrigin: Boolean = false) : OriginPolicy {

        private val normalized: Set<String> = origins.map { it.trimEnd('/').lowercase() }.toSet()

        override fun isAllowed(origin: String?): Boolean {
            if (origin == null) return allowNullOrigin
            return origin.trimEnd('/').lowercase() in normalized
        }
    }
}
