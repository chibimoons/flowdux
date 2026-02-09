package io.flowdux.remote

import io.flowdux.Action
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.transform

/**
 * Default implementation that wraps a raw [ClientConnection] with codecs
 * to produce a [TypedClientConnection].
 */
internal class DefaultTypedClientConnection<A : Action>(
    private val connection: ClientConnection,
    private val actionCodec: ActionCodec<A>,
    private val messageCodec: MessageCodec,
) : TypedClientConnection<A> {

    override val connectionState: StateFlow<ConnectionState>
        get() = connection.connectionState

    @Suppress("UNCHECKED_CAST")
    override val incoming: Flow<A> = connection.incoming.transform { raw ->
        val response = try {
            messageCodec.decodeServerMessage(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@transform // Skip malformed server messages
        }
        for (actionJson in response.actions) {
            val action = try {
                actionCodec.decode(actionJson)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue // Skip malformed actions
            }
            emit(action)
        }
    }

    override suspend fun send(action: A) {
        val actionJson = actionCodec.encode(action)
        val message = messageCodec.encodeActionMessage(actionJson)
        connection.send(message)
    }

    override suspend fun connect() {
        connection.connect()
    }

    override suspend fun disconnect() {
        connection.disconnect()
    }
}
