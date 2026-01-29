package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.ErrorProcessor
import io.flowdux.Reducer
import io.flowdux.State
import io.flowdux.remote.ActionCodec
import io.flowdux.remote.ClientSharedAction
import io.flowdux.remote.ServerSharedAction
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow

data class ServerState(val count: Int = 0) : State

sealed interface ServerAction : Action {
    // Lifecycle
    object StartListening : ServerAction

    // Client → Server (received from client via incoming)
    data class ClientAdd(val value: Int) : ServerAction, ServerSharedAction

    // Server → Client (intercepted by SRM)
    data class Add(val value: Int) : ServerAction, ClientSharedAction
    data class SetValue(val value: Int) : ServerAction, ClientSharedAction
    object Increment : ServerAction, ClientSharedAction

    // Server-internal only (passes through SRM, reaches reducer)
    data class InternalReset(val value: Int) : ServerAction
}

val serverReducer = Reducer<ServerState, ServerAction> { state, action ->
    when (action) {
        is ServerAction.StartListening -> state
        is ServerAction.ClientAdd -> state.copy(count = state.count + action.value)
        is ServerAction.Add -> state.copy(count = state.count + action.value)
        is ServerAction.SetValue -> state.copy(count = action.value)
        is ServerAction.Increment -> state.copy(count = state.count + 1)
        is ServerAction.InternalReset -> state.copy(count = action.value)
    }
}

val serverErrorProcessor = object : ErrorProcessor<ServerAction> {
    override fun process(throwable: Throwable): Flow<ServerAction> = emptyFlow()
}

class ServerActionCodec : ActionCodec<ServerAction> {
    override fun encode(action: ServerAction): String = when (action) {
        is ServerAction.StartListening -> """{"type":"StartListening"}"""
        is ServerAction.ClientAdd -> """{"type":"ClientAdd","value":${action.value}}"""
        is ServerAction.Add -> """{"type":"Add","value":${action.value}}"""
        is ServerAction.SetValue -> """{"type":"SetValue","value":${action.value}}"""
        is ServerAction.Increment -> """{"type":"Increment"}"""
        is ServerAction.InternalReset -> """{"type":"InternalReset","value":${action.value}}"""
    }

    override fun decode(json: String): ServerAction = when {
        json.contains("\"type\":\"StartListening\"") -> ServerAction.StartListening
        json.contains("\"type\":\"ClientAdd\"") -> {
            val value = Regex(""""value":(\d+)""").find(json)!!.groupValues[1].toInt()
            ServerAction.ClientAdd(value)
        }
        json.contains("\"type\":\"Add\"") -> {
            val value = Regex(""""value":(\d+)""").find(json)!!.groupValues[1].toInt()
            ServerAction.Add(value)
        }
        json.contains("\"type\":\"SetValue\"") -> {
            val value = Regex(""""value":(\d+)""").find(json)!!.groupValues[1].toInt()
            ServerAction.SetValue(value)
        }
        json.contains("\"type\":\"Increment\"") -> ServerAction.Increment
        json.contains("\"type\":\"InternalReset\"") -> {
            val value = Regex(""""value":(\d+)""").find(json)!!.groupValues[1].toInt()
            ServerAction.InternalReset(value)
        }
        else -> throw IllegalArgumentException("Unknown action JSON: $json")
    }
}

// -- Test SRM subclass --

class TestServerRemoteMiddleware(
    connection: ServerConnection,
    actionCodec: ActionCodec<ServerAction>,
    messageCodec: io.flowdux.remote.MessageCodec = io.flowdux.remote.serialization.JsonMessageCodec(),
) : ServerRemoteMiddleware<ServerState, ServerAction>(
    connection = connection,
    actionCodec = actionCodec,
    messageCodec = messageCodec,
) {
    override val processors: ActionProcessorMap<ServerState, ServerAction> = buildProcessors {
        on<ServerAction.StartListening> { _, _ ->
            startListening()
        }
    }
}

// -- Mock ServerConnection --

class MockServerConnection : ServerConnection {
    private val incomingChannel = Channel<String>(Channel.BUFFERED)
    override val incoming: Flow<String> = incomingChannel.receiveAsFlow()

    val sentMessages = mutableListOf<String>()

    override suspend fun send(message: String) {
        sentMessages.add(message)
    }

    /** Simulate receiving a message from the client. */
    suspend fun simulateClientMessage(message: String) {
        incomingChannel.send(message)
    }
}
