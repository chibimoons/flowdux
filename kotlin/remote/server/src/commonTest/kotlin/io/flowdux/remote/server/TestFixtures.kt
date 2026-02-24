package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.ErrorProcessor
import io.flowdux.Reducer
import io.flowdux.State
import io.flowdux.Store
import io.flowdux.remote.ClientSharedAction
import io.flowdux.remote.ServerSharedAction
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.middleware.InternalSendToClient
import io.flowdux.remote.server.middleware.InternalSessionListener
import io.flowdux.remote.server.middleware.InternalStartListening
import io.flowdux.remote.server.middleware.SingleClientSyncMiddleware
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow

data class ServerState(val count: Int = 0) : State

sealed interface ServerAction : Action {
    // Client → Server (received from client via incoming)
    data class ClientAdd(val value: Int) : ServerAction, ServerSharedAction

    // Server → Client (intercepted by SRM)
    data class Add(val value: Int) : ServerAction, ClientSharedAction
    data class SetValue(val value: Int) : ServerAction, ClientSharedAction
    object Increment : ServerAction, ClientSharedAction
    data class SyncState(val state: ServerState) : ServerAction, ClientSharedAction

    // Server-internal only (passes through SRM, reaches reducer)
    data class InternalReset(val value: Int) : ServerAction

    /** Triggers emit(ClientSharedAction) to test auto-dispatch */
    data class TriggerEmitClientAction(val value: Int) : ServerAction
}

val serverReducer = Reducer<ServerState, ServerAction> { state, action ->
    when (action) {
        is ServerAction.ClientAdd -> state.copy(count = state.count + action.value)
        is ServerAction.Add -> state.copy(count = state.count + action.value)
        is ServerAction.SetValue -> state.copy(count = action.value)
        is ServerAction.Increment -> state.copy(count = state.count + 1)
        is ServerAction.SyncState -> state // intercepted by SRM, never reaches reducer
        is ServerAction.InternalReset -> state.copy(count = action.value)
        is ServerAction.TriggerEmitClientAction -> state // handled by processor, not reducer
    }
}

val serverErrorProcessor = object : ErrorProcessor<ServerAction> {
    override fun process(throwable: Throwable): Flow<ServerAction> = emptyFlow()
}

/**
 * Middleware subclass whose processor emits a [ClientSharedAction].
 * Reproduces the scenario where the emitted action bypasses the middleware's
 * ClientSharedAction interception and is NOT sent to the client.
 */
class ProcessorEmittingMiddleware(
    connection: TypedServerConnection<ServerAction>,
) : SingleClientSyncMiddleware<ServerState, ServerAction>(
    connection = connection,
) {
    override val processors = buildProcessors {
        on<ServerAction.ClientAdd> { _, action ->
            emit(ServerAction.Add(action.value))
        }
    }
}

/**
 * Middleware subclass that emits a [ClientSharedAction] from a processor.
 * With [ClientSharedActionForwarder] (via createServerStore), the emitted action
 * is auto re-dispatched through the middleware pipeline and sent to the client.
 */
class EmitClientActionTestMiddleware(
    connection: TypedServerConnection<ServerAction>,
) : SingleClientSyncMiddleware<ServerState, ServerAction>(
    connection = connection,
) {
    override val processors = buildProcessors {
        on<ServerAction.TriggerEmitClientAction> { _, action ->
            // ClientSharedActionForwarder will auto re-dispatch this to the client
            emit(ServerAction.Add(action.value))
            // Also emit a local action to verify processor runs
            emit(ServerAction.InternalReset(1))
        }
    }
}

/** Dispatch [InternalStartListening] via unchecked cast (type-erased at runtime). */
@Suppress("UNCHECKED_CAST")
fun <S : State, A : Action> Store<S, A>.dispatchStartListening() {
    dispatch(InternalStartListening() as A)
}

/** Dispatch [InternalSessionListener] via unchecked cast (type-erased at runtime). */
@Suppress("UNCHECKED_CAST")
fun <S : State, A : Action> Store<S, A>.dispatchSessionListener(
    connection: TypedServerConnection<*>,
    onTerminate: (() -> Unit)? = null,
) {
    dispatch(InternalSessionListener(connection, onTerminate) as A)
}

/** Dispatch [InternalSendToClient] via unchecked cast (type-erased at runtime). */
@Suppress("UNCHECKED_CAST")
fun <S : State, A : Action> Store<S, A>.dispatchSendToClient(sessionId: String, action: Action) {
    dispatch(InternalSendToClient(sessionId, action) as A)
}

// -- Session-Aware Test Fixtures --

data class PokerState(
    val hands: Map<String, List<String>> = emptyMap(),
    val communityCards: List<String> = emptyList(),
) : State

sealed interface PokerAction : Action {
    /** Client → Server: a player takes an action. */
    data class PlayerAction(val playerId: String, val action: String) : PokerAction, io.flowdux.remote.ServerSharedAction

    /** Server → Client: send a player their personal view of the game. */
    data class SyncPlayerView(
        val hand: List<String>,
        val communityCards: List<String>,
    ) : PokerAction, io.flowdux.remote.ClientSharedAction

    /** Server-internal: deal cards. */
    data class DealCards(val hands: Map<String, List<String>>, val communityCards: List<String>) : PokerAction
}

val pokerReducer = Reducer<PokerState, PokerAction> { state, action ->
    when (action) {
        is PokerAction.PlayerAction -> state // handled externally
        is PokerAction.SyncPlayerView -> state // client-side only
        is PokerAction.DealCards -> state.copy(hands = action.hands, communityCards = action.communityCards)
    }
}

val pokerErrorProcessor = object : ErrorProcessor<PokerAction> {
    override fun process(throwable: Throwable): Flow<PokerAction> = emptyFlow()
}

// -- Mock TypedServerConnection --

class MockTypedServerConnection<A : Action> : TypedServerConnection<A> {
    override val isActive: Boolean = true
    private val incomingChannel = Channel<A>(Channel.BUFFERED)
    override val incoming: Flow<A> = incomingChannel.receiveAsFlow()

    val sentActions = mutableListOf<A>()

    override suspend fun send(action: A) {
        sentActions.add(action)
    }

    /** Simulate receiving a typed action from the client. */
    suspend fun simulateClientAction(action: A) {
        incomingChannel.send(action)
    }

    /** Close the incoming channel to simulate a clean disconnection. */
    fun closeIncoming() {
        incomingChannel.close()
    }

    /** Close the incoming channel with an error to simulate a connection failure. */
    fun closeIncomingWithError(cause: Throwable) {
        incomingChannel.close(cause)
    }
}
