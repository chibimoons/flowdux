package io.flowdux.remote.nodemediator

/**
 * Events emitted by [NodeMediator] and [CentralNodeManager] during operation.
 *
 * Pass an `onEvent` callback to the constructor to handle these events
 * (e.g., for logging or monitoring).
 *
 * ```kotlin
 * val mediator = NodeMediator(
 *     nodeId = "node-1",
 *     centralConnection = connection,
 *     scope = scope,
 *     onEvent = { event ->
 *         when (event) {
 *             is NodeMediatorEvent.MessageDropped -> logger.warn("Dropped: ${event.roomId}")
 *             is NodeMediatorEvent.RoutingStopped -> logger.info("Routing stopped", event.cause)
 *             is NodeMediatorEvent.ConnectionFailed -> logger.error("Connection failed", event.cause)
 *             is NodeMediatorEvent.NodeDisconnected -> logger.info("Node ${event.nodeId} disconnected")
 *             is NodeMediatorEvent.NodeConnected -> logger.info("Node ${event.nodeId} connected")
 *             is NodeMediatorEvent.NodeReconnected -> logger.info("Node ${event.nodeId} reconnected")
 *             is NodeMediatorEvent.CallbackFailed -> logger.error("Callback failed", event.cause)
 *         }
 *     },
 * )
 * ```
 */
sealed interface NodeMediatorEvent {
    /**
     * A message was dropped because no handler was registered for the room
     * and no [NodeMediator.onUnknownRoom] callback was provided.
     */
    data class MessageDropped(val roomId: String) : NodeMediatorEvent

    /**
     * Routing stopped due to a transport-layer failure.
     * Typically emitted when the Central connection drops unexpectedly.
     */
    data class RoutingStopped(val cause: Exception) : NodeMediatorEvent

    /**
     * The connection to the Central server failed to establish.
     * Specific to [NodeMediator] where the node initiates the connection.
     */
    data class ConnectionFailed(val cause: Exception) : NodeMediatorEvent

    /**
     * A node disconnected from the Central server.
     * Emitted by [CentralNodeManager] when a node's connection is lost.
     */
    data class NodeDisconnected(val nodeId: String, val cause: Exception?) : NodeMediatorEvent

    /**
     * A node connected to the Central server.
     * Emitted by [CentralNodeManager] when a node establishes a connection.
     */
    data class NodeConnected(val nodeId: String) : NodeMediatorEvent

    /**
     * A node reconnected to the Central server while its previous connection was still registered.
     * Emitted by [CentralNodeManager] when a node connects with a nodeId that is already present.
     * Room assignments are preserved across reconnection.
     */
    data class NodeReconnected(val nodeId: String) : NodeMediatorEvent

    /**
     * An [NodeMediator.onUnknownRoom] or [CentralNodeManager.onUpstreamAction] callback
     * threw an exception. Routing continues despite the failure.
     */
    data class CallbackFailed(val roomId: String, val cause: Exception) : NodeMediatorEvent
}
