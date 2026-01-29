package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.remote.ActionCodec
import io.flowdux.remote.MessageCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Default implementation that wraps a raw [ServerConnection] with codecs
 * to produce a [TypedServerConnection].
 */
internal class DefaultTypedServerConnection<A : Action>(
    private val connection: ServerConnection,
    private val actionCodec: ActionCodec<A>,
    private val messageCodec: MessageCodec,
) : TypedServerConnection<A> {

    override val incoming: Flow<A> = connection.incoming.map { raw ->
        val actionJson = messageCodec.decodeActionFromClient(raw)
        actionCodec.decode(actionJson)
    }

    override suspend fun send(action: A) {
        val actionJson = actionCodec.encode(action)
        val wireMessage = messageCodec.encodeServerResponse(listOf(actionJson))
        connection.send(wireMessage)
    }
}
