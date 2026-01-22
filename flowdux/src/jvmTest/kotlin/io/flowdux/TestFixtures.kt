package io.flowdux

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
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

    /**
     * Cancelable infinite stream action (default cancelable = true).
     * When a new instance is dispatched, the previous stream is cancelled.
     */
    data class InfiniteStreamAction(
        val id: String,
        val emitInterval: Long = 100L,
        val emitValue: Int = 1,
    ) : CounterAction, FlowHolderAction {
        override fun toFlowAction(): Flow<Action> = flow {
            while (true) {
                delay(emitInterval)
                emit(Add(emitValue))
            }
        }
    }

    /**
     * Non-cancelable FlowHolderAction.
     * Multiple streams can run concurrently.
     */
    data class NonCancelableStreamAction(
        val id: String,
        val values: List<Int>,
        val delayBetween: Long = 50L,
    ) : CounterAction, FlowHolderAction {
        override val cancelable: Boolean get() = false

        override fun toFlowAction(): Flow<Action> = flow {
            for (value in values) {
                delay(delayBetween)
                emit(Add(value))
            }
        }
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
            is CounterAction.InfiniteStreamAction -> state
            is CounterAction.NonCancelableStreamAction -> state
        }
    }

val testErrorProcessor = object : ErrorProcessor<CounterAction> {
    override fun process(throwable: Throwable): Flow<CounterAction> {
        return emptyFlow()
    }
}
