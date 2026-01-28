package io.flowdux.remote

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.ErrorProcessor
import io.flowdux.Middleware
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
    object LocalIncrement : TestAction
    object Connect : TestAction
}

val testReducer = Reducer<TestState, TestAction> { state, action ->
    when (action) {
        is TestAction.Add -> state.copy(count = state.count + action.value)
        is TestAction.SetMessage -> state.copy(message = action.message)
        is TestAction.ServerAdd -> state.copy(count = state.count + action.value)
        is TestAction.ServerSetMessage -> state.copy(message = action.message)
        is TestAction.LocalIncrement -> state.copy(count = state.count + 1)
        is TestAction.Connect -> state
    }
}

val testErrorProcessor = object : ErrorProcessor<TestAction> {
    override fun process(throwable: Throwable): Flow<TestAction> = emptyFlow()
}

// -- Test Action Codec --

class TestActionCodec : ActionCodec<TestAction> {
    override fun encode(action: TestAction): String {
        return when (action) {
            is TestAction.Add -> """{"type":"Add","value":${action.value}}"""
            is TestAction.SetMessage -> """{"type":"SetMessage","message":"${action.message}"}"""
            is TestAction.ServerAdd -> """{"type":"ServerAdd","value":${action.value}}"""
            is TestAction.ServerSetMessage -> """{"type":"ServerSetMessage","message":"${action.message}"}"""
            is TestAction.LocalIncrement -> """{"type":"LocalIncrement"}"""
            is TestAction.Connect -> """{"type":"Connect"}"""
        }
    }

    override fun decode(json: String): TestAction {
        return when {
            json.contains("\"type\":\"Add\"") -> {
                val value = Regex(""""value":(\d+)""").find(json)!!.groupValues[1].toInt()
                TestAction.Add(value)
            }
            json.contains("\"type\":\"SetMessage\"") -> {
                val message = Regex(""""message":"([^"]+)"""").find(json)!!.groupValues[1]
                TestAction.SetMessage(message)
            }
            json.contains("\"type\":\"ServerAdd\"") -> {
                val value = Regex(""""value":(\d+)""").find(json)!!.groupValues[1].toInt()
                TestAction.ServerAdd(value)
            }
            json.contains("\"type\":\"ServerSetMessage\"") -> {
                val message = Regex(""""message":"([^"]+)"""").find(json)!!.groupValues[1]
                TestAction.ServerSetMessage(message)
            }
            json.contains("\"type\":\"LocalIncrement\"") -> TestAction.LocalIncrement
            json.contains("\"type\":\"Connect\"") -> TestAction.Connect
            else -> throw IllegalArgumentException("Unknown action JSON: $json")
        }
    }
}

// -- Test ClientRemoteMiddleware subclass --

class TestClientRemoteMiddleware(
    connection: ClientConnection,
    actionCodec: ActionCodec<TestAction>,
    messageCodec: MessageCodec = JsonMessageCodec(),
    scope: CoroutineScope,
) : ClientRemoteMiddleware<TestState, TestAction>(
    connection = connection,
    actionCodec = actionCodec,
    messageCodec = messageCodec,
    scope = scope,
) {
    override val processors: ActionProcessorMap<TestState, TestAction> = buildProcessors {
        on<TestAction.Connect> { _, _ ->
            startConnection()
        }
    }
}

// -- Mock Client Connection --

class MockClientConnection(
    private val autoConnect: Boolean = true,
) : ClientConnection {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val incomingChannel = Channel<String>(Channel.BUFFERED)
    override val incoming: Flow<String> = incomingChannel.receiveAsFlow()

    val sentMessages = mutableListOf<String>()

    override suspend fun send(message: String) {
        sentMessages.add(message)
    }

    override suspend fun connect() {
        if (autoConnect) {
            _connectionState.value = ConnectionState.CONNECTED
        }
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** Simulate receiving a message from the server. */
    suspend fun simulateServerMessage(message: String) {
        incomingChannel.send(message)
    }

    /** Simulate connection state change. */
    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }
}
