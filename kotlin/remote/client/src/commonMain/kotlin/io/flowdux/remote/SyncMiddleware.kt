package io.flowdux.remote

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.ExecutionStrategy
import io.flowdux.FlowActionDelivery
import io.flowdux.FlowHolderAction
import io.flowdux.Middleware
import io.flowdux.State
import io.flowdux.concurrent
import io.flowdux.remote.usecase.ConnectionConfig
import io.flowdux.remote.usecase.ConnectionEvent
import io.flowdux.remote.usecase.ConnectionResult
import io.flowdux.remote.usecase.ConnectionUseCase
import io.flowdux.remote.usecase.ConnectionUseCaseImpl
import io.flowdux.remote.usecase.HealthCheckResult
import io.flowdux.remote.usecase.ReconnectState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
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
 *     on<ReconnectAction> { _, _ ->
 *         startReconnection { state ->
 *             when (state) {
 *                 is ReconnectState.Attempting -> MyAction.ReconnectAttempting(state.attempt)
 *                 is ReconnectState.Connected -> MyAction.ReconnectSuccess
 *                 is ReconnectState.Failed -> MyAction.ReconnectFailed(state.attempts)
 *                 else -> null
 *             }
 *         }
 *     }
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
 * **UseCase Pattern:** The middleware can be constructed with either a [TypedClientConnection]
 * (for backward compatibility) or a [ConnectionUseCase] (for advanced features like
 * exponential backoff reconnection and health checks).
 *
 * @param connectionUseCase The [ConnectionUseCase] for managing connection lifecycle.
 * @param scope Coroutine scope for background tasks. If not provided, an internal scope is created.
 *              The scope must have a [Job] in its context for proper cleanup. The scope is reusable
 *              across multiple connect/disconnect cycles.
 * @param onConnectionError Optional callback to convert connection errors to actions.
 *        When provided, connection failures will be dispatched through the store.
 */
open class SyncMiddleware<S : State, A : Action>(
    private val connectionUseCase: ConnectionUseCase<A>,
    scope: CoroutineScope? = null,
    private val onConnectionError: ((Throwable) -> A)? = null,
) : Middleware<S, A> {

    /**
     * Secondary constructor for backward compatibility.
     *
     * @param connection The [TypedClientConnection] for communicating with the server.
     * @param scope Coroutine scope for background tasks.
     * @param onConnectionError Optional callback to convert connection errors to actions.
     */
    constructor(
        connection: TypedClientConnection<A>,
        scope: CoroutineScope? = null,
        onConnectionError: ((Throwable) -> A)? = null,
    ) : this(
        connectionUseCase = ConnectionUseCaseImpl(connection),
        scope = scope,
        onConnectionError = onConnectionError,
    )

    /**
     * Constructor with custom connection configuration.
     *
     * @param connection The [TypedClientConnection] for communicating with the server.
     * @param config Configuration for reconnection and health checks.
     * @param scope Coroutine scope for background tasks.
     * @param onConnectionError Optional callback to convert connection errors to actions.
     */
    constructor(
        connection: TypedClientConnection<A>,
        config: ConnectionConfig,
        scope: CoroutineScope? = null,
        onConnectionError: ((Throwable) -> A)? = null,
    ) : this(
        connectionUseCase = ConnectionUseCaseImpl(connection, config),
        scope = scope,
        onConnectionError = onConnectionError,
    )

    private val actualScope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectionMutex = Mutex()
    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null
    private var healthCheckJob: Job? = null
    private var monitorJob: Job? = null
    private var listenerEmitted: Boolean = false
    private val internalActionChannel = Channel<A>(Channel.BUFFERED)

    init {
        actualScope.coroutineContext[Job]?.invokeOnCompletion { internalActionChannel.close() }
    }

    override val name: String = "SyncMiddleware"
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    /**
     * Access to the connection use case for advanced operations.
     */
    protected val useCase: ConnectionUseCase<A> get() = connectionUseCase

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
                when (val result = connectionUseCase.connect()) {
                    is ConnectionResult.Success -> { /* Connected successfully */ }
                    is ConnectionResult.Failure -> {
                        onConnectionError?.invoke(result.error)?.let { internalActionChannel.send(it) }
                    }
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
            connectionUseCase.disconnect()
            connectionJob?.cancel()
            connectionJob = null
            reconnectJob?.cancel()
            reconnectJob = null
            healthCheckJob?.cancel()
            healthCheckJob = null
            monitorJob?.cancel()
            monitorJob = null
        }
    }

    /**
     * Start reconnection with exponential backoff.
     *
     * Call this from within a processor to attempt reconnection.
     * The reconnection uses the configuration provided to the ConnectionUseCase.
     *
     * @param onStateChange Optional callback to convert reconnect states to actions.
     *        Return null to skip emitting an action for a particular state.
     */
    @Suppress("UNCHECKED_CAST")
    protected suspend fun FlowCollector<A>.startReconnection(
        onStateChange: ((ReconnectState) -> A?)? = null,
    ) {
        connectionMutex.withLock {
            reconnectJob?.cancel()
            reconnectJob = actualScope.launch {
                connectionUseCase.reconnect().collect { state ->
                    onStateChange?.invoke(state)?.let { action ->
                        internalActionChannel.send(action)
                    }
                }
            }
            if (!listenerEmitted) {
                listenerEmitted = true
                emit(ServerListenerAction() as A)
            }
        }
    }

    /**
     * Start health check monitoring.
     *
     * Call this from within a processor to start periodic health checks.
     * Health checks run in the background and continue until [stopConnection] is called.
     *
     * @param sendPing Function to send a ping and wait for pong response.
     * @param onResult Callback to convert health check results to actions.
     *        Return null to skip emitting an action for a particular result.
     */
    protected suspend fun startHealthCheck(
        sendPing: suspend () -> Unit,
        onResult: ((HealthCheckResult) -> A?)? = null,
    ): Job {
        return connectionMutex.withLock {
            healthCheckJob?.cancel()
            healthCheckJob = actualScope.launch {
                connectionUseCase.startHealthCheck(sendPing).collect { result ->
                    onResult?.invoke(result)?.let { action ->
                        internalActionChannel.send(action)
                    }
                }
            }
            healthCheckJob!!
        }
    }

    /**
     * Start connection state monitoring.
     *
     * Call this from within a processor to monitor connection state changes.
     * Monitoring runs in the background and continues until [stopConnection] is called.
     *
     * @param onEvent Callback to convert connection events to actions.
     *        Return null to skip emitting an action for a particular event.
     */
    protected suspend fun monitorConnection(
        onEvent: ((ConnectionEvent) -> A?)? = null,
    ): Job {
        return connectionMutex.withLock {
            monitorJob?.cancel()
            monitorJob = actualScope.launch {
                connectionUseCase.monitorState().collect { event ->
                    onEvent?.invoke(event)?.let { action ->
                        internalActionChannel.send(action)
                    }
                }
            }
            monitorJob!!
        }
    }

    override fun process(getState: () -> S, action: A): Flow<A> = flow {
        // 1. ServerSharedAction: send to server, do NOT emit locally
        if (action is ServerSharedAction) {
            connectionUseCase.send(action)
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
            connectionUseCase.incoming,
            internalActionChannel.receiveAsFlow(),
        )
    }
}
