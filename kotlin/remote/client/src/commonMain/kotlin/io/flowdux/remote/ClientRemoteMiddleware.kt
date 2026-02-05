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
 * @param scope Coroutine scope for background tasks. If not provided, an internal scope is created.
 *              The scope is reusable across multiple connect/disconnect cycles.
 */
open class ClientRemoteMiddleware<S : State, A : Action>(
    private val connection: TypedClientConnection<A>,
    scope: CoroutineScope? = null,
) : Middleware<S, A> {

    private val actualScope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectionJob: Job? = null

    override val name: String = "ClientRemoteMiddleware"
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    /**
     * Start the remote connection and emit a server listener [FlowHolderAction].
     *
     * Call this from within a processor to start listening for server messages.
     * The emitted FlowHolderAction uses [FlowActionDelivery.Dispatch] delivery,
     * so server actions are dispatched through the full middleware pipeline.
     *
     * If a previous connection job is still running, it will be cancelled before
     * starting a new connection.
     */
    @Suppress("UNCHECKED_CAST")
    protected suspend fun FlowCollector<A>.startConnection() {
        connectionJob?.cancel()
        connectionJob = actualScope.launch { connection.connect() }
        emit(ServerListenerAction() as A)
    }

    /**
     * Stop the remote connection and cancel the connection job.
     *
     * Call this from within a processor to disconnect from the server.
     * Only the connection job is cancelled, not the entire scope, allowing
     * reconnection via subsequent [startConnection] calls.
     */
    protected suspend fun stopConnection() {
        connection.disconnect()
        connectionJob?.cancel()
        connectionJob = null
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
