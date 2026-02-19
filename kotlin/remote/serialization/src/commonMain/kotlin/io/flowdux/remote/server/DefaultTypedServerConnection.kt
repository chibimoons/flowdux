package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.remote.ActionCodec
import io.flowdux.remote.MessageCodec
import io.flowdux.remote.server.connection.ServerConnection
import io.flowdux.remote.server.connection.TypedServerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Default implementation that wraps a raw [ServerConnection] with codecs
 * to produce a [TypedServerConnection].
 */
internal class DefaultTypedServerConnection<A : Action>(
    private val connection: ServerConnection,
    private val actionCodec: ActionCodec<A>,
    private val messageCodec: MessageCodec,
    private val onDecodeError: ((Exception) -> Unit)? = null,
) : TypedServerConnection<A> {

    override val isActive: Boolean get() = connection.isActive

    override val incoming: Flow<A> = connection.incoming.mapNotNull { raw ->
        try {
            val actionJson = messageCodec.decodeActionFromClient(raw)
            actionCodec.decode(actionJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onDecodeError?.invoke(e)
            null
        }
    }

    override suspend fun send(action: A) {
        val actionJson = actionCodec.encode(action)
        val wireMessage = messageCodec.encodeServerResponse(listOf(actionJson))
        connection.send(wireMessage)
    }
}
