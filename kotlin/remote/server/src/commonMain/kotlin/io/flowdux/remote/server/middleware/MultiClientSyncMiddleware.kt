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
import io.flowdux.remote.server.session.SessionRegistry
import io.flowdux.sequential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion

// -- New top-level FlowHolderActions (direct dispatch, no middleware intermediary) --

/**
 * FlowHolderAction that listens for incoming messages from a single client connection.
 * Uses [FlowActionDelivery.Dispatch] so received actions go through the full middleware pipeline.
 * Uses [concurrent] strategy to allow multiple listeners in parallel.
 *
 * Dispatched directly from [SharedStateServer.handleClient][io.flowdux.remote.server.pattern.SharedStateServer.handleClient].
 */
internal class InternalSessionListener(
    private val connection: TypedServerConnection<*>,
    private val onTerminate: (() -> Unit)? = null,
) : FlowHolderAction {
    override val delivery: FlowActionDelivery get() = FlowActionDelivery.Dispatch
    override val strategy: ExecutionStrategy get() = concurrent()

    override fun toFlowAction(): Flow<Action> = connection.incoming
        .map { it }
        .onCompletion { runCatching { onTerminate?.invoke() } }
}

/**
 * FlowHolderAction that broadcasts state changes to all clients.
 * Uses [FlowActionDelivery.Dispatch] so the state action goes through the middleware pipeline
 * (which will intercept [ClientSharedAction] and broadcast it).
 * Uses [sequential] strategy.
 *
 * Dispatched directly from [createSharedStateServer][io.flowdux.remote.server.pattern.createSharedStateServer].
 */
internal class InternalStateServing(
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
 * Uses [FlowActionDelivery.Dispatch] so [InternalSendToClient] goes through the middleware.
 * Uses [sequential] strategy.
 *
 * Dispatched directly from [createSessionAwareSharedStateServer][io.flowdux.remote.server.pattern.createSessionAwareSharedStateServer].
 */
internal class InternalPerSessionStateServing(
    private val stateFlow: StateFlow<*>,
    private val sessionStateMapper: (Any, String) -> Action?,
    private val registry: SessionRegistry<*>,
) : FlowHolderAction {
    override val delivery: FlowActionDelivery get() = FlowActionDelivery.Dispatch
    override val strategy: ExecutionStrategy get() = sequential()

    @Suppress("UNCHECKED_CAST")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun toFlowAction(): Flow<Action> = stateFlow.flatMapLatest { state ->
        flow {
            registry.sessionIds().forEach { sessionId ->
                sessionStateMapper(state as Any, sessionId)?.let { action ->
                    emit(InternalSendToClient(sessionId, action))
                }
            }
        }
    }
}

/**
 * Internal action dispatched to send an action to a specific client.
 */
class InternalSendToClient(
    val sessionId: String,
    val action: Action,
) : Action

/**
 * Internal middleware that routes actions for multiple client connections.
 *
 * Handles:
 * - [InternalSendToClient]: sends an action to a specific client
 * - [ClientSharedAction]: delegates to [SessionBroadcaster] for broadcasting (NOT emitted locally)
 * - Registered processors for server-side action handling
 * - Pass-through for all other actions
 *
 * Session management and serving setup are handled directly by
 * [SharedStateServer][io.flowdux.remote.server.pattern.SharedStateServer]
 * dispatching [FlowHolderAction]s ([InternalSessionListener], [InternalStateServing],
 * [InternalPerSessionStateServing]).
 *
 * Session storage is managed by [SessionRegistry][io.flowdux.remote.server.session.SessionRegistry];
 * this middleware handles action routing.
 *
 * @param processors Action processors for server-side action handling.
 * @param broadcaster Session broadcaster for sending actions to clients.
 */
class MultiClientSyncMiddleware<S : State, A : Action>(
    override val processors: ActionProcessorMap<S, A> = emptyMap(),
    internal val broadcaster: SessionBroadcaster<A>,
) : Middleware<S, A> {

    @Suppress("UNCHECKED_CAST")
    override fun process(getState: () -> S, action: A): Flow<A> = flow {
        // 1. InternalSendToClient: send action to specific client
        if (action is InternalSendToClient) {
            broadcaster.sendToClient(action.sessionId, action.action as A)
            return@flow
        }

        // 2. ClientSharedAction: broadcast to all clients, do NOT emit locally
        if (action is ClientSharedAction) {
            broadcaster.broadcast(action)
            return@flow
        }

        // 3. Check processors for local action handling
        val processor = processors[action::class]
        if (processor != null) {
            processor.invoke(this, getState(), action)
            return@flow
        }

        // 4. Pass through unhandled actions
        emit(action)
    }
}
