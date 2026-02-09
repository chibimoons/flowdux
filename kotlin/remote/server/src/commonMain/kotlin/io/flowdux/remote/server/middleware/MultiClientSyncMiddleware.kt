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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Internal action dispatched to start listening for incoming messages from a client connection.
 */
class InternalAddSession<A : Action>(
    val sessionId: String,
    val connection: TypedServerConnection<A>,
) : Action

/**
 * Internal middleware that routes actions for multiple client connections.
 *
 * Handles:
 * - [InternalAddSession]: emits a [FlowHolderAction] to listen for client messages
 * - [ClientSharedAction]: delegates to [SessionBroadcaster] for broadcasting (NOT emitted locally)
 * - Registered processors for server-side action handling
 * - Pass-through for all other actions
 *
 * Session storage is managed by [SessionRegistry][io.flowdux.remote.server.session.SessionRegistry];
 * this middleware only handles action routing.
 *
 * @param processors Action processors for server-side action handling.
 * @param broadcaster Session broadcaster for sending actions to clients.
 */
class MultiClientSyncMiddleware<S : State, A : Action>(
    override val processors: ActionProcessorMap<S, A> = emptyMap(),
    private val broadcaster: SessionBroadcaster<A>,
) : Middleware<S, A> {

    override val name: String = "MultiClientSyncMiddleware"

    @Suppress("UNCHECKED_CAST")
    override fun process(getState: () -> S, action: A): Flow<A> = flow {
        // 0. InternalAddSession: emit listener FlowHolderAction
        if (action is InternalAddSession<*>) {
            val addAction = action as InternalAddSession<A>
            emit(SessionListenerAction(addAction.connection) as A)
            return@flow
        }

        // 1. ClientSharedAction: broadcast to all clients, do NOT emit locally
        if (action is ClientSharedAction) {
            broadcaster.broadcast(action)
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
}
