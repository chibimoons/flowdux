package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.remote.ActionCodec
import io.flowdux.remote.MessageCodec

/**
 * Wraps a [ServerConnection] with an [ActionCodec] and [MessageCodec] to produce a [TypedServerConnection].
 *
 * @param actionCodec Codec for serializing/deserializing actions.
 * @param messageCodec Codec for wire-level message framing.
 */
fun <A : Action> ServerConnection.typed(
    actionCodec: ActionCodec<A>,
    messageCodec: MessageCodec,
): TypedServerConnection<A> = DefaultTypedServerConnection(this, actionCodec, messageCodec)
