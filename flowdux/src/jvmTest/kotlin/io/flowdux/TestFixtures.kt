package io.flowdux

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Common test fixtures shared across all test files.
 */

data class CounterState(val count: Int = 0) : State

sealed interface CounterAction : Action {
    object Increment : CounterAction
    object Decrement : CounterAction
    data class Add(val value: Int) : CounterAction
    data class SetValue(val value: Int) : CounterAction
    data class FetchData(val id: String) : CounterAction
    data class FetchDataSuccess(val id: String, val value: Int) : CounterAction
    data class FetchDataError(val id: String, val error: String) : CounterAction
    object Reset : CounterAction

    data class StreamConnected(
        private val valueFlow: Flow<Int>,
    ) : CounterAction, FlowHolderAction {
        override fun toFlowAction(): Flow<Action> = valueFlow.map { Add(it) }
    }

    data class MultiStreamConnected(
        private val flow1: Flow<Int>,
        private val flow2: Flow<Int>,
    ) : CounterAction, FlowHolderAction {
        override fun toFlowAction() = listOf(
            flow1.map { Add(it) },
            flow2.map { Add(it) }
        ).merge()
    }
}

val counterReducer =
    Reducer<CounterState, CounterAction> { state, action ->
        when (action) {
            is CounterAction.Increment -> state.copy(count = state.count + 1)
            is CounterAction.Decrement -> state.copy(count = state.count - 1)
            is CounterAction.Add -> state.copy(count = state.count + action.value)
            is CounterAction.SetValue -> state.copy(count = action.value)
            is CounterAction.FetchData -> state
            is CounterAction.FetchDataSuccess -> state.copy(count = action.value)
            is CounterAction.FetchDataError -> state
            is CounterAction.Reset -> state.copy(count = 0)
            is CounterAction.StreamConnected -> state
            is CounterAction.MultiStreamConnected -> state
        }
    }

val testErrorProcessor = object : ErrorProcessor<CounterAction> {
    override fun process(throwable: Throwable): Flow<CounterAction> {
        return emptyFlow()
    }
}
