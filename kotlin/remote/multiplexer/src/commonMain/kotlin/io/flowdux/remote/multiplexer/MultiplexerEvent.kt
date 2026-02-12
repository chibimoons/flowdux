package io.flowdux.remote.multiplexer

/**
 * Events emitted by [ServerConnectionMultiplexer] and [ClientConnectionMultiplexer]
 * during routing.
 *
 * Pass an `onEvent` callback to the multiplexer constructor to handle these events
 * (e.g., for logging or monitoring). By default, events are not reported via this callback;
 * transport errors ([RoutingStopped]) still propagate to the scope unless handled via `onEvent`.
 *
 * ```kotlin
 * val mux = ClientConnectionMultiplexer(
 *     physicalConnection = physical,
 *     scope = scope,
 *     onEvent = { event ->
 *         when (event) {
 *             is MultiplexerEvent.MessageDropped -> logger.warn("Dropped: ${event.roomId}")
 *             is MultiplexerEvent.RoutingStopped -> logger.info("Routing stopped", event.cause)
 *             is MultiplexerEvent.ConnectionFailed -> logger.error("Connection failed", event.cause)
 *             is MultiplexerEvent.CallbackFailed -> logger.error("Callback failed", event.cause)
 *         }
 *     },
 * )
 * ```
 */
sealed interface MultiplexerEvent {
    /**
     * A message was dropped because the room's channel buffer was full or closed.
     */
    data class MessageDropped(val roomId: String) : MultiplexerEvent

    /**
     * Routing stopped due to a transport error or end of the incoming flow.
     * This is expected when the remote peer disconnects or the network drops.
     */
    data class RoutingStopped(val cause: Exception) : MultiplexerEvent

    /**
     * The physical connection failed to establish or was lost.
     * This is specific to [ClientConnectionMultiplexer] where the client initiates the connection.
     */
    data class ConnectionFailed(val cause: Exception) : MultiplexerEvent

    /**
     * The [ServerConnectionMultiplexer.onUnknownRoom] callback threw an exception.
     * Routing continues despite the failure.
     */
    data class CallbackFailed(val roomId: String, val cause: Exception) : MultiplexerEvent
}
