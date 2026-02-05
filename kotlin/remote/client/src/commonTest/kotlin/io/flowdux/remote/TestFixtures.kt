package io.flowdux.remote

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.ErrorProcessor
import io.flowdux.Reducer
import io.flowdux.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow

// -- Test State & Actions --

data class TestState(val count: Int = 0, val message: String = "") : State

sealed interface TestAction : Action {
    data class Add(val value: Int) : TestAction
    data class SetMessage(val message: String) : TestAction
    data class ServerAdd(val value: Int) : TestAction, ServerSharedAction
    data class ServerSetMessage(val message: String) : TestAction, ServerSharedAction
    data class ConnectionError(val message: String) : TestAction
    object LocalIncrement : TestAction
    object Connect : TestAction
}

val testReducer = Reducer<TestState, TestAction> { state, action ->
    when (action) {
        is TestAction.Add -> state.copy(count = state.count + action.value)
        is TestAction.SetMessage -> state.copy(message = action.message)
        is TestAction.ServerAdd -> state.copy(count = state.count + action.value)
        is TestAction.ServerSetMessage -> state.copy(message = action.message)
        is TestAction.ConnectionError -> state.copy(message = action.message)
        is TestAction.LocalIncrement -> state.copy(count = state.count + 1)
        is TestAction.Connect -> state
    }
}

val testErrorProcessor = object : ErrorProcessor<TestAction> {
    override fun process(throwable: Throwable): Flow<TestAction> = emptyFlow()
}

// -- Test ClientRemoteMiddleware subclass --

class TestClientRemoteMiddleware(
    connection: TypedClientConnection<TestAction>,
    scope: CoroutineScope,
    onConnectionError: ((Throwable) -> TestAction)? = null,
) : ClientRemoteMiddleware<TestState, TestAction>(
    connection = connection,
    scope = scope,
    onConnectionError = onConnectionError,
) {
    override val processors: ActionProcessorMap<TestState, TestAction> = buildProcessors {
        on<TestAction.Connect> { _, _ ->
            startConnection()
        }
    }
}

// -- Mock TypedClientConnection --

class MockTypedClientConnection<A : Action>(
    private val autoConnect: Boolean = true,
    private val connectException: Exception? = null,
) : TypedClientConnection<A> {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val incomingChannel = Channel<A>(Channel.BUFFERED)
    override val incoming: Flow<A> = incomingChannel.receiveAsFlow()

    val sentActions = mutableListOf<A>()

    override suspend fun send(action: A) {
        sentActions.add(action)
    }

    override suspend fun connect() {
        connectException?.let { throw it }
        if (autoConnect) {
            _connectionState.value = ConnectionState.CONNECTED
        }
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** Simulate receiving a typed action from the server. */
    suspend fun simulateServerAction(action: A) {
        incomingChannel.send(action)
    }

    /** Simulate connection state change. */
    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }
}
