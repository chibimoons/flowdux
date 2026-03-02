package io.flowdux.remote.ktor

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close

/**
 * Check the `Origin` header of the current WebSocket session against the given [policy].
 *
 * If the origin is not allowed, the session is closed with
 * [CloseReason.Codes.VIOLATED_POLICY] and this function returns `false`.
 *
 * Usage inside a `webSocket` handler:
 * ```kotlin
 * webSocket("/ws") {
 *     if (!checkOrigin(policy)) return@webSocket
 *     // proceed normally
 * }
 * ```
 *
 * @param policy the [OriginPolicy] to evaluate.
 * @return `true` if the origin is allowed; `false` if the session was closed.
 */
suspend fun DefaultWebSocketServerSession.checkOrigin(policy: OriginPolicy): Boolean {
    val origin = call.request.headers["Origin"]
    if (policy.isAllowed(origin)) return true
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origin not allowed"))
    return false
}

/**
 * Install a WebSocket route with automatic origin validation.
 *
 * Equivalent to calling [webSocket] and invoking [checkOrigin] at the start:
 * ```kotlin
 * webSocketWithOriginCheck("/ws", policy) {
 *     val conn = KtorWebSocketServerConnection(this)
 *     // handle connection ...
 * }
 * ```
 *
 * If the client's `Origin` header is rejected, the session is closed with
 * [CloseReason.Codes.VIOLATED_POLICY] before [handler] is invoked.
 *
 * @param path the WebSocket endpoint path.
 * @param originPolicy the [OriginPolicy] to enforce.
 * @param handler the WebSocket session handler, invoked only when the origin is allowed.
 */
fun Route.webSocketWithOriginCheck(
    path: String,
    originPolicy: OriginPolicy,
    handler: suspend DefaultWebSocketServerSession.() -> Unit,
) {
    webSocket(path) {
        if (!checkOrigin(originPolicy)) return@webSocket
        handler()
    }
}
