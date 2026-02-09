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
import io.flowdux.sequential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

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
 * - [ClientSharedAction]: delegates to [SessionBroadcaster] for broadcasting (NOT emitted locally)
 * - Registered processors for server-side action handling
 * - Pass-through for all other actions
 *
 * Session storage is managed by [SessionRegistry][io.flowdux.remote.server.session.SessionRegistry];
 * this middleware handles action routing and session management.
 *
 * @param processors Action processors for server-side action handling.
 * @param broadcaster Session broadcaster for sending actions to clients.
 */
class MultiClientSyncMiddleware<S : State, A : Action>(
    override val processors: ActionProcessorMap<S, A> = emptyMap(),
    internal val broadcaster: SessionBroadcaster<A>,
) : Middleware<S, A> {

    override val name: String = "MultiClientSyncMiddleware"

    @Suppress("UNCHECKED_CAST")
    override fun process(getState: () -> S, action: A): Flow<A> = flow {
        // 0. InternalAddSession: register session and emit listener FlowHolderAction
        if (action is InternalAddSession) {
            broadcaster.registry.addSession(
                action.sessionId,
                action.connection as TypedServerConnection<A>,
            )
            emit(SessionListenerAction(action.connection as TypedServerConnection<A>) as A)
            return@flow
        }

        // 1. InternalRemoveSession: remove session from registry
        if (action is InternalRemoveSession) {
            broadcaster.registry.removeSession(action.sessionId)
            return@flow
        }

        // 2. InternalSendToClient: send action to specific client
        if (action is InternalSendToClient) {
            broadcaster.sendToClient(action.sessionId, action.action as A)
            return@flow
        }

        // 3. InternalStartServing: emit FlowHolderAction for state broadcasting
        if (action is InternalStartServing) {
            emit(StateServingAction(action.stateFlow, action.stateMapper) as A)
            return@flow
        }

        // 4. InternalStartServingPerSession: emit FlowHolderAction for per-session state
        if (action is InternalStartServingPerSession) {
            emit(SessionStateServingAction(action.stateFlow, action.sessionStateMapper) as A)
            return@flow
        }

        // 5. ClientSharedAction: broadcast to all clients, do NOT emit locally
        if (action is ClientSharedAction) {
            broadcaster.broadcast(action)
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

        override fun toFlowAction(): Flow<Action> = connection.incoming.map { it }
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
    private inner class SessionStateServingAction(
        private val stateFlow: StateFlow<*>,
        private val sessionStateMapper: (Any, String) -> Action?,
    ) : FlowHolderAction {
        override val delivery: FlowActionDelivery get() = FlowActionDelivery.Dispatch
        override val strategy: ExecutionStrategy get() = sequential()

        @Suppress("UNCHECKED_CAST")
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        override fun toFlowAction(): Flow<Action> = stateFlow.flatMapLatest { state ->
            flow {
                broadcaster.registry.sessionIds().forEach { sessionId ->
                    sessionStateMapper(state as Any, sessionId)?.let { action ->
                        emit(InternalSendToClient(sessionId, action))
                    }
                }
            }
        }
    }
}
