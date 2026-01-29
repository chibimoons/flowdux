package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.ErrorProcessor
import io.flowdux.Reducer
import io.flowdux.State
import io.flowdux.Store
import io.flowdux.remote.ClientSharedAction
import io.flowdux.remote.ServerSharedAction
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
}

val serverReducer = Reducer<ServerState, ServerAction> { state, action ->
    when (action) {
        is ServerAction.ClientAdd -> state.copy(count = state.count + action.value)
        is ServerAction.Add -> state.copy(count = state.count + action.value)
        is ServerAction.SetValue -> state.copy(count = action.value)
        is ServerAction.Increment -> state.copy(count = state.count + 1)
        is ServerAction.SyncState -> state // intercepted by SRM, never reaches reducer
        is ServerAction.InternalReset -> state.copy(count = action.value)
    }
}

val serverErrorProcessor = object : ErrorProcessor<ServerAction> {
    override fun process(throwable: Throwable): Flow<ServerAction> = emptyFlow()
}

/**
 * SRM subclass whose processor emits a [ClientSharedAction].
 * Reproduces the scenario where the emitted action bypasses the SRM's
 * ClientSharedAction interception and is NOT sent to the client.
 */
class ProcessorEmittingSRM(
    connection: TypedServerConnection<ServerAction>,
) : ServerRemoteMiddleware<ServerState, ServerAction>(
    connection = connection,
) {
    override val processors = buildProcessors {
        on<ServerAction.ClientAdd> { _, action ->
            emit(ServerAction.Add(action.value))
        }
    }
}

/** Dispatch [InternalStartListening] via unchecked cast (type-erased at runtime). */
@Suppress("UNCHECKED_CAST")
fun <S : State, A : Action> Store<S, A>.dispatchStartListening() {
    dispatch(InternalStartListening() as A)
}

// -- Mock TypedServerConnection --

class MockTypedServerConnection<A : Action> : TypedServerConnection<A> {
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
}
