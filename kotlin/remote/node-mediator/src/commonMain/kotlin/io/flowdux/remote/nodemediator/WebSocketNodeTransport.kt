package io.flowdux.remote.nodemediator

import io.flowdux.Action
import io.flowdux.remote.TypedClientConnection
import kotlinx.coroutines.flow.Flow

/**
 * [NodeTransport] implementation backed by a [TypedClientConnection] (e.g. WebSocket).
 *
 * In a WebSocket-based architecture, the Central server handles room-level routing,
 * so [subscribeRoom] and [unsubscribeRoom] are no-ops.
 *
 * @param A The type of actions being transported
 * @param connection The typed client connection to the Central server
 */
class WebSocketNodeTransport<A : Action>(
    private val connection: TypedClientConnection<NodeAction<A>>,
) : NodeTransport<A> {
    override val incoming: Flow<NodeAction<A>> = connection.incoming
    override suspend fun send(action: NodeAction<A>) = connection.send(action)
    override suspend fun subscribeRoom(roomId: String) { /* no-op: Central handles routing */ }
    override suspend fun unsubscribeRoom(roomId: String) { /* no-op */ }
    override suspend fun connect() = connection.connect()
    override suspend fun disconnect() = connection.disconnect()
}
