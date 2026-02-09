package io.flowdux.remote.server.connection

import io.flowdux.Action
import kotlinx.coroutines.flow.Flow

/**
 * A typed wrapper around [ServerConnection] that handles serialization internally.
 *
 * Instead of sending/receiving raw strings, consumers work directly with typed actions.
 * This decouples the middleware from codec details.
 */
interface TypedServerConnection<A : Action> {
    /** Incoming actions from the client, already decoded. */
    val incoming: Flow<A>

    /** Send a typed action to the client (encoding handled internally). */
    suspend fun send(action: A)
}
