package io.flowdux.remote.ktor

import io.flowdux.remote.server.ServerConnection
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Ktor-based WebSocket server implementation of [ServerConnection].
 *
 * Wraps a Ktor [WebSocketSession] to provide a [ServerConnection]
 * for use with [ServerRemoteMiddleware][io.flowdux.remote.server.ServerRemoteMiddleware].
 *
 * @param session Ktor WebSocket session (typically a server session from a `webSocket` route)
 */
class KtorWebSocketServerConnection(
    private val session: WebSocketSession,
) : ServerConnection {

    override val incoming: Flow<String> = session.incoming.receiveAsFlow()
        .filterIsInstance<Frame.Text>()
        .map { it.readText() }

    override suspend fun send(message: String) {
        session.outgoing.send(Frame.Text(message))
    }
}
