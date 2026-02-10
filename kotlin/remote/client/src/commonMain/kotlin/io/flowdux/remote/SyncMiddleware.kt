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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * **Threading:** The [startConnection] and [stopConnection] methods use internal synchronization
 * to handle concurrent calls safely. However, for best practices, consider using an execution
 * strategy like `takeLatest()` on Connect/Disconnect actions to prevent redundant operations.
 *
 * **Connection Lifecycle:** The [ServerListenerAction] is emitted immediately when [startConnection]
 * is called, before the connection is fully established. This allows the listener to be ready to
 * receive messages as soon as the connection completes. The connection implementation should handle
 * this by buffering or suspending until connected.
 *
 * @param connection The [TypedClientConnection] for communicating with the server.
 * @param scope Coroutine scope for background tasks. If not provided, an internal scope is created.
 *              The scope must have a [Job] in its context for proper cleanup. The scope is reusable
 *              across multiple connect/disconnect cycles.
 * @param onConnectionError Optional callback to convert connection errors to actions.
 *        When provided, connection failures will be dispatched through the store.
 */
open class SyncMiddleware<S : State, A : Action>(
    private val connection: TypedClientConnection<A>,
    scope: CoroutineScope? = null,
    private val onConnectionError: ((Throwable) -> A)? = null,
) : Middleware<S, A> {

    private val actualScope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectionMutex = Mutex()
    private var connectionJob: Job? = null
    private var listenerEmitted: Boolean = false
    private val errorChannel = Channel<A>(Channel.BUFFERED)

    init {
        actualScope.coroutineContext[Job]?.invokeOnCompletion { errorChannel.close() }
    }

    override val name: String = "SyncMiddleware"
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    /**
     * Start the remote connection and emit a server listener [FlowHolderAction].
     *
     * Call this from within a processor to start listening for server messages.
     * The emitted FlowHolderAction uses [FlowActionDelivery.Dispatch] delivery,
     * so server actions are dispatched through the full middleware pipeline.
     *
     * If a previous connection job is still running, it will be cancelled before
     * starting a new connection. Connection errors are dispatched through the store
     * if [onConnectionError] callback is provided.
     *
     * **Note:** The [ServerListenerAction] is only emitted on the first call. Subsequent calls
     * will restart the connection but reuse the existing listener to prevent duplicate message
     * processing. The listener continues to receive from `connection.incoming` across reconnects.
     */
    @Suppress("UNCHECKED_CAST")
    protected suspend fun FlowCollector<A>.startConnection() {
        connectionMutex.withLock {
            connectionJob?.cancel()
            connectionJob = actualScope.launch {
                try {
                    connection.connect()
                } catch (e: CancellationException) {
                    throw e // Propagate cancellation
                } catch (e: Exception) {
                    onConnectionError?.invoke(e)?.let { errorChannel.send(it) }
                }
            }
            if (!listenerEmitted) {
                listenerEmitted = true
                emit(ServerListenerAction() as A)
            }
        }
    }

    /**
     * Stop the remote connection and cancel the connection job.
     *
     * Call this from within a processor to disconnect from the server.
     * Only the connection job is cancelled, not the entire scope, allowing
     * reconnection via subsequent [startConnection] calls.
     */
    protected suspend fun stopConnection() {
        connectionMutex.withLock {
            connection.disconnect()
            connectionJob?.cancel()
            connectionJob = null
        }
    }

    /**
     * Send an action directly to the server, bypassing the middleware pipeline.
     *
     * Use this method when you need to send a [ServerSharedAction] from within a processor.
     * Actions emitted via [FlowCollector.emit] do not go through the middleware pipeline again,
     * so [ServerSharedAction]s emitted from processors would go directly to the Reducer
     * instead of being sent to the server.
     *
     * Example:
     * ```kotlin
     * override val processors = buildProcessors {
     *     on<SomeAction> { _, action ->
     *         sendToServer(MyServerAction("hello"))  // Sent to server
     *         emit(LocalStateUpdate(...))            // Goes to Reducer
     *     }
     * }
     * ```
     *
     * @param action The action to send to the server.
     */
    @Suppress("UNCHECKED_CAST")
    protected suspend fun sendToServer(action: ServerSharedAction) {
        connection.send(action as A)
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
