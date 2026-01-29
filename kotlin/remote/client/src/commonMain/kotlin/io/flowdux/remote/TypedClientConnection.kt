package io.flowdux.remote

import io.flowdux.Action
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A typed wrapper around [ClientConnection] that handles serialization internally.
 *
 * Instead of sending/receiving raw strings, consumers work directly with typed actions.
 * This decouples the middleware from codec details.
 */
interface TypedClientConnection<A : Action> {
    /** Current connection state as a reactive flow. */
    val connectionState: StateFlow<ConnectionState>

    /** Incoming actions from the server, already decoded. */
    val incoming: Flow<A>

    /** Send a typed action to the server (encoding handled internally). */
    suspend fun send(action: A)

    /** Establish the connection. */
    suspend fun connect()

    /** Close the connection. */
    suspend fun disconnect()
}
