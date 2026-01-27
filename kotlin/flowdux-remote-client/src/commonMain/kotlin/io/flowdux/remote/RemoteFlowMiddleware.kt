package io.flowdux.remote

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.Middleware
import io.flowdux.State
import io.flowdux.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Middleware that intercepts [SharedAction]s and sends them to a remote server,
 * then dispatches server responses back into the local store.
 *
 * Data flow:
 * ```
 * dispatch(SharedAction) → middleware intercepts → serialize & send via connection
 *                        → NOT emitted locally
 *
 * Server response received → deserialize actions → dispatch to local store
 * ```
 *
 * Non-[SharedAction] actions pass through unmodified.
 *
 * @param connection The transport layer for communicating with the server.
 * @param actionCodec Codec for serializing/deserializing actions.
 * @param messageCodec Codec for wire-level message framing. Defaults to [JsonMessageCodec].
 * @param config Configuration options.
 * @param scope Coroutine scope for background tasks (connection listening, buffer flushing).
 */
open class RemoteFlowMiddleware<S : State, A : Action>(
    private val connection: RemoteConnection,
    private val actionCodec: ActionCodec<A>,
    private val messageCodec: MessageCodec = JsonMessageCodec(),
    private val config: RemoteFlowConfig = RemoteFlowConfig(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : Middleware<S, A> {

    override val name: String = "RemoteFlowMiddleware"
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    /**
     * Tracks actions that originated from the server to prevent re-sending them.
     * Uses identity (===) comparison to avoid false positives with data class value equality.
     */
    private val serverOriginatedActions = mutableListOf<A>()
    private val serverActionsMutex = Mutex()

    private val buffer = mutableListOf<String>()
    private val bufferMutex = Mutex()

    private var dispatchToStore: ((A) -> Unit)? = null

    /**
     * Bind the middleware to a store and start the server connection.
     *
     * This must be called after the store is created. It starts listening
     * for server messages and initiates the connection.
     *
     * @param store The store to dispatch server-originated actions to.
     */
    fun connectTo(store: Store<S, A>) {
        dispatchToStore = store::dispatch
        startServerListener()
        scope.launch { connection.connect() }
    }

    override fun process(getState: () -> S, action: A): Flow<A> = flow {
        // Check if this action originated from the server (identity comparison)
        val isServerAction = removeServerOriginatedAction(action)
        if (isServerAction) {
            emit(action)
            return@flow
        }

        // Non-SharedAction: pass through to local processing
        if (action !is SharedAction) {
            emit(action)
            return@flow
        }

        // SharedAction: send to server, do NOT emit locally
        sendToServer(action)
    }

    private suspend fun removeServerOriginatedAction(action: A): Boolean {
        return serverActionsMutex.withLock {
            // Use identity comparison (===) to avoid data class value equality false positives
            val idx = serverOriginatedActions.indexOfFirst { it === action }
            if (idx >= 0) {
                serverOriginatedActions.removeAt(idx)
                true
            } else {
                false
            }
        }
    }

    private suspend fun sendToServer(action: A) {
        val actionJson = actionCodec.encode(action)
        val message = messageCodec.encodeActionMessage(actionJson)

        if (connection.connectionState.value == ConnectionState.CONNECTED) {
            connection.send(message)
        } else if (config.bufferWhileDisconnected) {
            bufferMutex.withLock {
                if (buffer.size >= config.maxBufferSize) {
                    buffer.removeAt(0)
                }
                buffer.add(message)
            }
        }
    }

    private fun startServerListener() {
        // Listen for incoming server messages
        scope.launch {
            connection.incoming.collect { raw ->
                handleServerMessage(raw)
            }
        }
        // Flush buffer on reconnection
        scope.launch {
            connection.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    flushBuffer()
                }
            }
        }
    }

    private suspend fun handleServerMessage(raw: String) {
        val response = messageCodec.decodeServerMessage(raw)

        for (actionJson in response.actions) {
            val action = actionCodec.decode(actionJson)
            serverActionsMutex.withLock {
                serverOriginatedActions.add(action)
            }
            dispatchToStore?.invoke(action)
        }
    }

    private suspend fun flushBuffer() {
        val messages = bufferMutex.withLock {
            val copy = buffer.toList()
            buffer.clear()
            copy
        }
        for (message in messages) {
            connection.send(message)
        }
    }
}
