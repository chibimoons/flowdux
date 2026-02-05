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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Client-side middleware that intercepts [ServerSharedAction]s and sends them to the server,
 * and listens for server responses via a [FlowHolderAction]-based server listener.
 *
 * Data flow:
 * ```
 * dispatch(ServerSharedAction) -> middleware intercepts -> connection.send(action)
 *                              -> NOT emitted locally
 *
 * startConnection() -> emits ServerListenerAction (FlowHolderAction)
 *   -> FlowHolderMiddleware resolves -> listens for server messages
 *   -> server actions dispatched through full middleware pipeline
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
 * @param connection The [TypedClientConnection] for communicating with the server.
 * @param scope Coroutine scope for background tasks. If not provided, an internal scope is created
 *              and will be cancelled on [stopConnection]. If provided, the caller is responsible
 *              for managing the scope's lifecycle.
 */
open class ClientRemoteMiddleware<S : State, A : Action>(
    private val connection: TypedClientConnection<A>,
    scope: CoroutineScope? = null,
) : Middleware<S, A> {

    private val internalJob: Job? = if (scope == null) SupervisorJob() else null
    private val actualScope: CoroutineScope = scope ?: CoroutineScope(internalJob!! + Dispatchers.Default)

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
        actualScope.launch { connection.connect() }
        emit(ServerListenerAction() as A)
    }

    /**
     * Stop the remote connection and cancel the internal scope if one was created.
     *
     * Call this from within a processor to disconnect from the server.
     * If an internal scope was created (no scope parameter provided to constructor),
     * it will be cancelled to prevent resource leaks.
     */
    protected suspend fun stopConnection() {
        connection.disconnect()
        internalJob?.cancel()
    }

    override fun process(getState: () -> S, action: A): Flow<A> = flow {
        // 1. ServerSharedAction: send to server, do NOT emit locally
        if (action is ServerSharedAction) {
            connection.send(action)
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

    private inner class ServerListenerAction : FlowHolderAction {
        override val delivery: FlowActionDelivery get() = FlowActionDelivery.Dispatch
        override val strategy: ExecutionStrategy get() = concurrent()

        override fun toFlowAction(): Flow<Action> = connection.incoming.map { it }
    }
}
