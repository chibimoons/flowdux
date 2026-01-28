package io.flowdux.remote

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.ExecutionStrategy
import io.flowdux.FlowActionDelivery
import io.flowdux.FlowHolderAction
import io.flowdux.Middleware
import io.flowdux.State
import io.flowdux.concurrent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/**
 * Client-side middleware that intercepts [ServerSharedAction]s and sends them to the server,
 * and listens for server responses via a [FlowHolderAction]-based server listener.
 *
 * Data flow:
 * ```
 * dispatch(ServerSharedAction) → middleware intercepts → serialize & send via connection
 *                              → NOT emitted locally
 *
 * startConnection() → emits ServerListenerAction (FlowHolderAction)
 *   → FlowHolderMiddleware resolves → listens for server messages
 *   → server actions dispatched through full middleware pipeline
 * ```
 *
 * Non-[ServerSharedAction] actions pass through unmodified, unless a processor is registered.
 *
 * Server response actions should implement [ClientSharedAction] instead of [ServerSharedAction]
 * to avoid being re-sent to the server when dispatched through the pipeline.
 *
 * Subclasses should override [processors] to handle specific actions:
 * ```kotlin
 * override val processors = buildProcessors {
 *     on<ConnectAction> { _, _ -> startConnection() }
 *     on<DisconnectAction> { _, _ -> stopConnection() }
 * }
 * ```
 *
 * @param connection The transport layer for communicating with the server.
 * @param actionCodec Codec for serializing/deserializing actions.
 * @param messageCodec Codec for wire-level message framing. Defaults to [JsonMessageCodec].
 * @param scope Coroutine scope for background tasks.
 */
open class ClientRemoteMiddleware<S : State, A : Action>(
    private val connection: RemoteConnection,
    private val actionCodec: ActionCodec<A>,
    private val messageCodec: MessageCodec = JsonMessageCodec(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : Middleware<S, A> {

    override val name: String = "ClientRemoteMiddleware"
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    /**
     * Start the remote connection and emit a server listener [FlowHolderAction].
     *
     * Call this from within a processor to start listening for server messages.
     * The emitted FlowHolderAction uses [FlowActionDelivery.Dispatch] delivery,
     * so server actions are dispatched through the full middleware pipeline.
     */
    @Suppress("UNCHECKED_CAST")
    protected suspend fun FlowCollector<A>.startConnection() {
        scope.launch { connection.connect() }
        emit(ServerListenerAction() as A)
    }

    /**
     * Stop the remote connection.
     *
     * Call this from within a processor to disconnect from the server.
     */
    protected suspend fun stopConnection() {
        connection.disconnect()
    }

    override fun process(getState: () -> S, action: A): Flow<A> = flow {
        // 1. ServerSharedAction: send to server, do NOT emit locally
        if (action is ServerSharedAction) {
            sendToServer(action)
            return@flow
        }

        // 2. Check processors for local action handling
        val processor = processors[action::class]
        if (processor != null) {
            processor.invoke(this, getState(), action)
            return@flow
        }

        // 3. Pass through unhandled actions
        emit(action)
    }

    private suspend fun sendToServer(action: A) {
        val actionJson = actionCodec.encode(action)
        val message = messageCodec.encodeActionMessage(actionJson)
        connection.send(message)
    }

    @Suppress("UNCHECKED_CAST")
    private inner class ServerListenerAction : FlowHolderAction {
        override val delivery: FlowActionDelivery get() = FlowActionDelivery.Dispatch
        override val strategy: ExecutionStrategy get() = concurrent()

        override fun toFlowAction(): Flow<Action> = connection.incoming.transform { raw ->
            val response = messageCodec.decodeServerMessage(raw)
            for (actionJson in response.actions) {
                emit(actionCodec.decode(actionJson) as Action)
            }
        }
    }
}
