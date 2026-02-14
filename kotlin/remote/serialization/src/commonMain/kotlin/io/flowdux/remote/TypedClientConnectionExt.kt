package io.flowdux.remote

import io.flowdux.Action

/**
 * Wraps a [ClientConnection] with an [ActionCodec] and [MessageCodec] to produce a [TypedClientConnection].
 *
 * @param actionCodec Codec for serializing/deserializing actions.
 * @param messageCodec Codec for wire-level message framing.
 */
fun <A : Action> ClientConnection.typed(
    actionCodec: ActionCodec<A>,
    messageCodec: MessageCodec,
    onDecodeError: ((Exception) -> Unit)? = null,
): TypedClientConnection<A> = DefaultTypedClientConnection(this, actionCodec, messageCodec, onDecodeError)
