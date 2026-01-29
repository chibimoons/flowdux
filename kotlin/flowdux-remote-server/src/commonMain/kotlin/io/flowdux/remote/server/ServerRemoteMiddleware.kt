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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Server-side middleware that intercepts [ClientSharedAction]s and sends them to the client,
 * and listens for client messages via a [FlowHolderAction]-based client listener.
 *
 * This is the server-side counterpart to [ClientRemoteMiddleware][io.flowdux.remote.ClientRemoteMiddleware]:
 * - Client CRM: intercepts [ServerSharedAction][io.flowdux.remote.ServerSharedAction]s → sends to server, consumed locally
 * - **Server SRM: intercepts [ClientSharedAction]s → sends to client, consumed locally**
 *
 * Data flow:
 * ```
 * dispatch(ClientSharedAction) → middleware intercepts → connection.send(action)
 *                               → NOT emitted locally
 *
 * startListening() → emits ClientListenerAction (FlowHolderAction)
 *   → FlowHolderMiddleware resolves → listens for client messages
 *   → client actions dispatched through full middleware pipeline
 * ```
 *
 * Non-[ClientSharedAction] actions pass through unmodified, unless a processor is registered.
 *
 * Subclasses should override [processors] to handle lifecycle actions:
 * ```kotlin
 * class MyChatSRM(
 *     connection: TypedServerConnection<ChatAction>,
 * ) : ServerRemoteMiddleware<ChatState, ChatAction>(
 *     connection = connection,
 * ) {
 *     override val processors = buildProcessors {
 *         on<ChatAction.StartListening> { _, _ -> startListening() }
 *     }
 * }
 * ```
 *
 * @param connection The [TypedServerConnection] for communicating with the client.
 */
open class ServerRemoteMiddleware<S : State, A : Action>(
    private val connection: TypedServerConnection<A>,
) : Middleware<S, A> {

    override val name: String = "ServerRemoteMiddleware"
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    /**
     * Start listening for incoming client messages.
     *
     * Call this from within a processor to start receiving client messages.
     * The emitted FlowHolderAction uses [FlowActionDelivery.Dispatch] delivery,
     * so client actions are dispatched through the full middleware pipeline.
     */
    @Suppress("UNCHECKED_CAST")
    protected suspend fun FlowCollector<A>.startListening() {
        emit(ClientListenerAction() as A)
    }

    /**
     * Send an action to the client.
     *
     * Call this from within a processor to send a custom message to the client.
     */
    protected suspend fun sendToClient(action: A) {
        connection.send(action)
    }

    override fun process(getState: () -> S, action: A): Flow<A> = flow {
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
