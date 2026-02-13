package io.flowdux.remote.nodemediator.transport

import io.flowdux.Action
import io.flowdux.remote.nodemediator.NodeAction
import kotlinx.coroutines.flow.Flow

/**
 * Transport abstraction for [NodeMediator] communication with the Central server.
 *
 * Decouples [NodeMediator] from the underlying transport mechanism (WebSocket, Kafka, etc.).
 * Implementations handle the physical delivery of [NodeAction] messages.
 *
 * @param A The type of actions being transported
 * @see WebSocketNodeTransport
 */
interface NodeTransport<A : Action> {
    /** Incoming actions from the Central server. */
    val incoming: Flow<NodeAction<A>>

    /** Sends an action to the Central server. */
    suspend fun send(action: NodeAction<A>)

    /**
     * Subscribes to actions for the given room.
     *
     * For topic-based transports (e.g. Kafka), this subscribes to the room's topic.
     * For connection-based transports (e.g. WebSocket), this is a no-op since
     * the Central server handles routing.
     */
    suspend fun subscribeRoom(roomId: String)

    /**
     * Unsubscribes from actions for the given room.
     *
     * For topic-based transports (e.g. Kafka), this unsubscribes from the room's topic.
     * For connection-based transports (e.g. WebSocket), this is a no-op.
     */
    suspend fun unsubscribeRoom(roomId: String)

    /** Establishes the transport connection. */
    suspend fun connect()

    /** Closes the transport connection. */
    suspend fun disconnect()
}
