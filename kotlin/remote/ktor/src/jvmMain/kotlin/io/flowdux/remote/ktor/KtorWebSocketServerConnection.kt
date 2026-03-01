package io.flowdux.remote.ktor

import io.flowdux.remote.server.connection.ServerConnection
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive

/**
 * Ktor-based WebSocket server implementation of [ServerConnection].
 *
 * Wraps a Ktor [WebSocketSession] to provide a [ServerConnection]
 * for use with [SingleClientSyncMiddleware][io.flowdux.remote.server.middleware.SingleClientSyncMiddleware].
 *
 * @param session Ktor WebSocket session (typically a server session from a `webSocket` route)
 */
class KtorWebSocketServerConnection(private val session: WebSocketSession) : ServerConnection {
    override val isActive: Boolean get() = session.isActive

    override val incoming: Flow<String> =
        session.incoming
            .receiveAsFlow()
            .filterIsInstance<Frame.Text>()
            .map { it.readText() }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override suspend fun send(message: String) {
        try {
            session.outgoing.send(Frame.Text(message))
        } catch (_: ClosedSendChannelException) {
            // Session already closed; ignore silently
        } catch (e: CancellationException) {
            // Check if this is external coroutine cancellation or just session closure
            // Note: isClosedForSend is marked as DelicateCoroutinesApi but is the correct
            // way to distinguish between session closure and external cancellation
            if (!session.outgoing.isClosedForSend) {
                throw e // Propagate external coroutine cancellation
            }
            // Session was closed; ignore silently (same as ClosedSendChannelException)
        }
    }
}
