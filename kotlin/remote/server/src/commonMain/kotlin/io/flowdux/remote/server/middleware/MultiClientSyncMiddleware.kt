package io.flowdux.remote.server.middleware

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.ExecutionStrategy
import io.flowdux.FlowActionDelivery
import io.flowdux.FlowHolderAction
import io.flowdux.Middleware
import io.flowdux.State
import io.flowdux.concurrent
import io.flowdux.remote.ClientSharedAction
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.session.SessionBroadcaster
import io.flowdux.remote.server.usecase.CleanupResult
import io.flowdux.remote.server.usecase.SessionConfig
import io.flowdux.remote.server.usecase.SessionEvent
import io.flowdux.remote.server.usecase.SessionUseCase
import io.flowdux.remote.server.usecase.SessionUseCaseImpl
import io.flowdux.sequential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Internal action dispatched to add a session and start listening for incoming messages.
 */
class InternalAddSession(
    val sessionId: String,
    val connection: TypedServerConnection<*>,
) : Action

/**
 * Internal action dispatched to remove a session.
 */
class InternalRemoveSession(
    val sessionId: String,
) : Action

/**
 * Internal action dispatched to send an action to a specific client.
 */
class InternalSendToClient(
    val sessionId: String,
    val action: Action,
) : Action

/**
 * Internal action dispatched to start serving state to all clients.
 * The middleware will emit a [FlowHolderAction] that broadcasts state changes.
 */
class InternalStartServing(
    val stateFlow: StateFlow<*>,
    val stateMapper: (Any) -> Action,
) : Action

/**
 * Internal action dispatched to start serving per-session state to clients.
 * The middleware will emit a [FlowHolderAction] that sends personalized state to each session.
 */
class InternalStartServingPerSession(
    val stateFlow: StateFlow<*>,
    val sessionStateMapper: (Any, String) -> Action?,
) : Action

/**
 * Internal middleware that routes actions for multiple client connections.
 *
 * Handles:
 * - [InternalAddSession]: registers session and emits a [FlowHolderAction] to listen for client messages
 * - [InternalRemoveSession]: removes a session from the registry
 * - [InternalSendToClient]: sends an action to a specific client
 * - [InternalStartServing]: emits a [FlowHolderAction] to broadcast state changes
 * - [InternalStartServingPerSession]: emits a [FlowHolderAction] to send per-session state
 * - [ClientSharedAction]: delegates to [SessionUseCase] for broadcasting (NOT emitted locally)
 * - Registered processors for server-side action handling
 * - Pass-through for all other actions
 *
 * Session storage is managed by [SessionRegistry][io.flowdux.remote.server.session.SessionRegistry];
 * this middleware handles action routing and session management.
 *
 * @param processors Action processors for server-side action handling.
 * @param sessionUseCase Session use case for session management.
 * @param scope Coroutine scope for background tasks.
 */
class MultiClientSyncMiddleware<S : State, A : Action>(
    override val processors: ActionProcessorMap<S, A> = emptyMap(),
    private val sessionUseCase: SessionUseCase<A>,
    scope: CoroutineScope? = null,
) : Middleware<S, A> {

    /**
     * Constructor for backward compatibility with [SessionBroadcaster].
     *
     * @param processors Action processors for server-side action handling.
     * @param broadcaster Session broadcaster for sending actions to clients.
     */
    constructor(
        processors: ActionProcessorMap<S, A> = emptyMap(),
        broadcaster: SessionBroadcaster<A>,
    ) : this(
        processors = processors,
        sessionUseCase = SessionUseCaseImpl(broadcaster.registry, broadcaster),
        scope = null,
    )

    /**
     * Constructor for backward compatibility with [SessionBroadcaster] and custom session configuration.
     *
     * @param processors Action processors for server-side action handling.
     * @param broadcaster Session broadcaster for sending actions to clients.
     * @param config Configuration for session management.
     * @param scope Coroutine scope for background tasks.
     */
    constructor(
        processors: ActionProcessorMap<S, A> = emptyMap(),
        broadcaster: SessionBroadcaster<A>,
        config: SessionConfig,
        scope: CoroutineScope? = null,
    ) : this(
        processors = processors,
        sessionUseCase = SessionUseCaseImpl(broadcaster.registry, broadcaster, config),
        scope = scope,
    )

    private val actualScope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var cleanupJob: Job? = null
    private val eventChannel = Channel<A>(Channel.BUFFERED)

    override val name: String = "MultiClientSyncMiddleware"

    /**
     * Access to the session use case for advanced operations.
     */
    protected val useCase: SessionUseCase<A> get() = sessionUseCase

    /**
     * Start session monitoring.
     *
     * Listens for session events and converts them to actions.
     * Monitoring runs in the background and continues until cancelled.
     *
     * @param onEvent Callback to convert session events to actions.
     *        Return null to skip emitting an action for a particular event.
     */
    fun startMonitoring(
        onEvent: ((SessionEvent) -> A?)? = null,
    ): Job {
        monitorJob?.cancel()
        monitorJob = actualScope.launch {
            sessionUseCase.monitorSessions().collect { event ->
                onEvent?.invoke(event)?.let { action ->
                    eventChannel.send(action)
                }
            }
        }
        return monitorJob!!
    }

    /**
     * Start automatic idle session cleanup.
     *
     * Periodically cleans up idle sessions according to the configuration.
     * Cleanup runs in the background and continues until cancelled.
     *
     * @param config Session configuration (uses the one provided during construction by default).
     * @param onCleanup Optional callback after each cleanup run.
     */
    fun startAutoCleanup(
        config: SessionConfig = SessionConfig(),
        onCleanup: (suspend (CleanupResult) -> Unit)? = null,
    ): Job {
        cleanupJob?.cancel()
        cleanupJob = actualScope.launch {
            kotlinx.coroutines.delay(config.cleanupInterval)
            while (true) {
                val result = sessionUseCase.cleanupIdleSessions()
                onCleanup?.invoke(result)
                kotlinx.coroutines.delay(config.cleanupInterval)
            }
        }
        return cleanupJob!!
    }

    /**
     * Stop session monitoring.
     */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * Stop automatic cleanup.
     */
    fun stopAutoCleanup() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    @Suppress("UNCHECKED_CAST")
    override fun process(getState: () -> S, action: A): Flow<A> = flow {
        // 0. InternalAddSession: register session and emit listener FlowHolderAction
        if (action is InternalAddSession) {
            sessionUseCase.addSession(
                action.sessionId,
                action.connection as TypedServerConnection<A>,
            )
            emit(SessionListenerAction(action.connection as TypedServerConnection<A>) as A)
            return@flow
        }

        // 1. InternalRemoveSession: remove session from registry
        if (action is InternalRemoveSession) {
            sessionUseCase.removeSession(action.sessionId)
            return@flow
        }

        // 2. InternalSendToClient: send action to specific client
        if (action is InternalSendToClient) {
            sessionUseCase.sendToClient(action.sessionId, action.action as A)
            return@flow
        }

        // 3. InternalStartServing: emit FlowHolderAction for state broadcasting
        if (action is InternalStartServing) {
            emit(StateServingAction(action.stateFlow, action.stateMapper) as A)
            return@flow
        }

        // 4. InternalStartServingPerSession: emit FlowHolderAction for per-session state
        if (action is InternalStartServingPerSession) {
            emit(
                SessionStateServingAction(
                    stateFlow = action.stateFlow,
                    sessionStateMapper = action.sessionStateMapper,
                    getSessionIds = { sessionUseCase.sessionIds() },
                ) as A,
            )
            return@flow
        }

        // 5. ClientSharedAction: broadcast to all clients, do NOT emit locally
        if (action is ClientSharedAction) {
            sessionUseCase.broadcast(action as A)
            return@flow
        }

        // 6. Check processors for local action handling
        val processor = processors[action::class]
        if (processor != null) {
            processor.invoke(this, getState(), action)
            return@flow
        }

        // 7. Pass through unhandled actions
        emit(action)
    }

    /**
     * FlowHolderAction that listens for incoming messages from a single client connection.
     * Uses [FlowActionDelivery.Dispatch] so received actions go through the full middleware pipeline.
     * Uses [concurrent] strategy to allow multiple listeners in parallel.
     */
    private inner class SessionListenerAction(
        private val connection: TypedServerConnection<A>,
    ) : FlowHolderAction {
        override val delivery: FlowActionDelivery get() = FlowActionDelivery.Dispatch
        override val strategy: ExecutionStrategy get() = concurrent()

        override fun toFlowAction(): Flow<Action> = merge(
            connection.incoming.map { it },
            eventChannel.receiveAsFlow(),
        )
    }

    /**
     * FlowHolderAction that broadcasts state changes to all clients.
     * Uses [FlowActionDelivery.Dispatch] so the state action goes through the middleware pipeline
     * (which will intercept ClientSharedAction and broadcast it).
     * Uses [sequential] strategy.
     */
    private inner class StateServingAction(
        private val stateFlow: StateFlow<*>,
        private val stateMapper: (Any) -> Action,
    ) : FlowHolderAction {
        override val delivery: FlowActionDelivery get() = FlowActionDelivery.Dispatch
        override val strategy: ExecutionStrategy get() = sequential()

        @Suppress("UNCHECKED_CAST")
        override fun toFlowAction(): Flow<Action> = stateFlow.map { state ->
            stateMapper(state as Any)
        }
    }

    /**
     * FlowHolderAction that sends per-session state to each client.
     * Uses [FlowActionDelivery.Dispatch] so InternalSendToClient goes through the middleware.
     * Uses [sequential] strategy.
     */
    private class SessionStateServingAction(
        private val stateFlow: StateFlow<*>,
        private val sessionStateMapper: (Any, String) -> Action?,
        private val getSessionIds: suspend () -> Set<String>,
    ) : FlowHolderAction {
        override val delivery: FlowActionDelivery get() = FlowActionDelivery.Dispatch
        override val strategy: ExecutionStrategy get() = sequential()

        @Suppress("UNCHECKED_CAST")
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        override fun toFlowAction(): Flow<Action> = stateFlow.flatMapLatest { state ->
            flow {
                getSessionIds().forEach { sessionId ->
                    sessionStateMapper(state as Any, sessionId)?.let { action ->
                        emit(InternalSendToClient(sessionId, action))
                    }
                }
            }
        }
    }
}
