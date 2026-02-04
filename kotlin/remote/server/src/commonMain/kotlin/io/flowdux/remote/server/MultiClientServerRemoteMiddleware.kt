package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.ExecutionStrategy
import io.flowdux.FlowActionDelivery
import io.flowdux.FlowHolderAction
import io.flowdux.Middleware
import io.flowdux.State
import io.flowdux.concurrent
import io.flowdux.remote.ClientSharedAction
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Internal action dispatched by [RemoteServerSession.handleClient] to register a new client session.
 */
internal class InternalAddSession<A : Action>(
    val sessionId: String,
    val connection: TypedServerConnection<A>,
) : Action

/**
 * Internal action dispatched by [RemoteServerSession.handleClient] to remove a client session.
 */
internal class InternalRemoveSession(val sessionId: String) : Action

/**
 * Internal middleware that manages multiple client connections for [RemoteServerSession].
 *
 * Handles:
 * - [InternalAddSession]: registers a session and emits a [FlowHolderAction] to listen for client messages
 * - [InternalRemoveSession]: removes a session
 * - [ClientSharedAction]: broadcasts to all connected clients (NOT emitted locally)
 * - Registered processors for server-side action handling
 * - Pass-through for all other actions
 *
 * @param processors Action processors for server-side action handling.
 */
internal class MultiClientServerRemoteMiddleware<S : State, A : Action>(
    override val processors: ActionProcessorMap<S, A> = emptyMap(),
) : Middleware<S, A> {

    override val name: String = "MultiClientServerRemoteMiddleware"

    private val sessions = mutableMapOf<String, TypedServerConnection<A>>()
    private val mutex = Mutex()

    /** Snapshot of currently connected session IDs. */
    suspend fun sessionIds(): Set<String> = mutex.withLock { sessions.keys.toSet() }

    /** Number of currently connected sessions. */
    suspend fun sessionCount(): Int = mutex.withLock { sessions.size }

    /**
     * Send an action to a specific client by session ID.
     * No-op if the session does not exist.
     */
    suspend fun sendToClient(sessionId: String, action: A) {
        val connection = mutex.withLock { sessions[sessionId] } ?: return
        try {
            connection.send(action)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Isolate send failures
        }
    }

    /**
     * Send an action to all connected clients.
     * Errors on individual connections are caught and do not affect others.
     */
    suspend fun broadcast(action: A) {
        val snapshot = mutex.withLock { sessions.values.toList() }
        for (connection in snapshot) {
            try {
                connection.send(action)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Isolate per-client send failures
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun process(getState: () -> S, action: A): Flow<A> = flow {
        // 0. InternalAddSession: register session and emit listener FlowHolderAction
        if (action is InternalAddSession<*>) {
            val addAction = action as InternalAddSession<A>
            mutex.withLock {
                sessions[addAction.sessionId] = addAction.connection
            }
            emit(SessionListenerAction(addAction.connection) as A)
            return@flow
        }

        // 1. InternalRemoveSession: unregister session
        if (action is InternalRemoveSession) {
            mutex.withLock {
                sessions.remove(action.sessionId)
            }
            return@flow
        }

        // 2. ClientSharedAction: broadcast to all clients, do NOT emit locally
        if (action is ClientSharedAction) {
            broadcast(action)
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
