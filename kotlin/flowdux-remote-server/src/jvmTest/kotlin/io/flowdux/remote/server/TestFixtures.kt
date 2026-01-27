package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.ErrorProcessor
import io.flowdux.Reducer
import io.flowdux.State
import io.flowdux.remote.ActionCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class ServerState(val count: Int = 0) : State

sealed interface ServerAction : Action {
    data class Add(val value: Int) : ServerAction
    data class SetValue(val value: Int) : ServerAction
    object Increment : ServerAction
}

val serverReducer = Reducer<ServerState, ServerAction> { state, action ->
    when (action) {
        is ServerAction.Add -> state.copy(count = state.count + action.value)
        is ServerAction.SetValue -> state.copy(count = action.value)
        is ServerAction.Increment -> state.copy(count = state.count + 1)
    }
}

val serverErrorProcessor = object : ErrorProcessor<ServerAction> {
    override fun process(throwable: Throwable): Flow<ServerAction> = emptyFlow()
}

class ServerActionCodec : ActionCodec<ServerAction> {
    override fun encode(action: ServerAction): String = when (action) {
        is ServerAction.Add -> """{"type":"Add","value":${action.value}}"""
        is ServerAction.SetValue -> """{"type":"SetValue","value":${action.value}}"""
        is ServerAction.Increment -> """{"type":"Increment"}"""
    }

    override fun decode(json: String): ServerAction = when {
        json.contains("\"type\":\"Add\"") -> {
            val value = Regex(""""value":(\d+)""").find(json)!!.groupValues[1].toInt()
            ServerAction.Add(value)
        }
        json.contains("\"type\":\"SetValue\"") -> {
            val value = Regex(""""value":(\d+)""").find(json)!!.groupValues[1].toInt()
            ServerAction.SetValue(value)
        }
        json.contains("\"type\":\"Increment\"") -> ServerAction.Increment
        else -> throw IllegalArgumentException("Unknown action JSON: $json")
    }
}
