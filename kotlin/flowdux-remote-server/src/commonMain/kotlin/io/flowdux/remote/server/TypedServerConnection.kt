package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.remote.ActionCodec
import io.flowdux.remote.MessageCodec
import io.flowdux.remote.serialization.JsonMessageCodec
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

/**
 * Wraps a [ServerConnection] with an [ActionCodec] and [MessageCodec] to produce a [TypedServerConnection].
 *
 * @param actionCodec Codec for serializing/deserializing actions.
 * @param messageCodec Codec for wire-level message framing.
 */
fun <A : Action> ServerConnection.typed(
    actionCodec: ActionCodec<A>,
    messageCodec: MessageCodec = JsonMessageCodec(),
): TypedServerConnection<A> = DefaultTypedServerConnection(this, actionCodec, messageCodec)
