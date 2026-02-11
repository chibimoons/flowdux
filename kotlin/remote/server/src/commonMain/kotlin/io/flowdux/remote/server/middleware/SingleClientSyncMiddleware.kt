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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Internal action dispatched by [serve] to trigger client message listening.
 * Not intended for direct use — use [Store.serve][serve] instead.
 */
internal class InternalStartListening : Action

/**
 * Server-side middleware that intercepts [ClientSharedAction]s and sends them to the client,
 * and listens for client messages via a [FlowHolderAction]-based client listener.
 *
 * This is the server-side counterpart to [SyncMiddleware][io.flowdux.remote.SyncMiddleware]:
 * - Client SyncMiddleware: intercepts [ServerSharedAction][io.flowdux.remote.ServerSharedAction]s → sends to server, NOT emitted locally
 * - **Server SingleClientSyncMiddleware: intercepts [ClientSharedAction]s → sends to client, NOT emitted locally**
 *
 * Data flow:
 * ```
 * dispatch(ClientSharedAction) → middleware intercepts → connection.send(action)
 *                               → NOT emitted locally
 *
 * serve() → dispatches InternalStartListening → emits ClientListenerAction (FlowHolderAction)
 *   → FlowHolderMiddleware resolves → listens for client messages
 *   → client actions dispatched through full middleware pipeline
 * ```
 *
 * Non-[ClientSharedAction] actions pass through unmodified, unless a processor is registered.
 *
 * Typical usage with [serve]:
 * ```kotlin
 * createGameStore(session).serve { state ->
 *     SyncState(GameState(score = state.score))
 * }
 * ```
 *
 * @param connection The [TypedServerConnection] for communicating with the client.
 */
open class SingleClientSyncMiddleware<S : State, A : Action>(
    private val connection: TypedServerConnection<A>,
) : Middleware<S, A> {

    override val name: String = "SingleClientSyncMiddleware"
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    /**
     * Send an action directly to the client, bypassing the middleware pipeline.
     *
     * @param action The action to send to the client.
     */
    @Deprecated(
        message = "No longer needed. Emit ClientSharedAction from a processor and use " +
            "createServerStore() for automatic re-dispatch through the pipeline.",
        level = DeprecationLevel.WARNING,
    )
    @Suppress("UNCHECKED_CAST")
    protected suspend fun sendToClient(action: ClientSharedAction) {
        connection.send(action as A)
    }

    @Suppress("UNCHECKED_CAST")
    override fun process(getState: () -> S, action: A): Flow<A> = flow {
        // 0. InternalStartListening: emitted by serve(), triggers client listener
        if (action is InternalStartListening) {
            emit(ClientListenerAction() as A)
            return@flow
        }

        // 1. ClientSharedAction: send to client, do NOT emit locally
        if (action is ClientSharedAction) {
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

    private inner class ClientListenerAction : FlowHolderAction {
        override val delivery: FlowActionDelivery get() = FlowActionDelivery.Dispatch
        override val strategy: ExecutionStrategy get() = concurrent()

        override fun toFlowAction(): Flow<Action> = connection.incoming.map { it }
    }
}
