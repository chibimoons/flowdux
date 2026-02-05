package io.flowdux.remote

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.ExecutionStrategy
import io.flowdux.FlowActionDelivery
import io.flowdux.FlowHolderAction
import io.flowdux.Middleware
import io.flowdux.State
import io.flowdux.concurrent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Client-side middleware that intercepts [ServerSharedAction]s and sends them to the server,
 * and listens for server responses via a [FlowHolderAction]-based server listener.
 *
 * Data flow:
 * ```
 * dispatch(ServerSharedAction) → middleware intercepts → connection.send(action)
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
 * @param connection The [TypedClientConnection] for communicating with the server.
 * @param scope Coroutine scope for background tasks.
 * @param onConnectionError Optional callback to convert connection errors to actions.
 *        When provided, connection failures will be dispatched through the store.
 */
open class ClientRemoteMiddleware<S : State, A : Action>(
    private val connection: TypedClientConnection<A>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onConnectionError: ((Throwable) -> A)? = null,
) : Middleware<S, A> {

    override val name: String = "ClientRemoteMiddleware"
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    private val errorChannel = Channel<A>(Channel.BUFFERED)

    /**
     * Start the remote connection and emit a server listener [FlowHolderAction].
     *
     * Call this from within a processor to start listening for server messages.
     * The emitted FlowHolderAction uses [FlowActionDelivery.Dispatch] delivery,
     * so server actions are dispatched through the full middleware pipeline.
     */
    @Suppress("UNCHECKED_CAST")
    protected suspend fun FlowCollector<A>.startConnection() {
        scope.launch {
            try {
                connection.connect()
            } catch (e: CancellationException) {
                throw e // Don't catch cancellation
            } catch (e: Exception) {
                onConnectionError?.invoke(e)?.let { errorChannel.send(it) }
            }
        }
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

        override fun toFlowAction(): Flow<Action> = merge(
            connection.incoming.map { it },
            errorChannel.receiveAsFlow(),
        )
    }
}
