package io.flowdux.remote

/**
 * Configuration for [RemoteFlowMiddleware].
 *
 * @property bufferWhileDisconnected When true, shared actions dispatched while disconnected
 *   are buffered and sent when the connection is re-established.
 * @property maxBufferSize Maximum number of actions to buffer while disconnected.
 *   Oldest actions are dropped when the buffer is full.
 */
data class RemoteFlowConfig(
    val bufferWhileDisconnected: Boolean = true,
    val maxBufferSize: Int = 100,
)
