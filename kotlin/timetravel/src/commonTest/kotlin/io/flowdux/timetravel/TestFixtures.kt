package io.flowdux.timetravel

import io.flowdux.Action
import io.flowdux.ErrorProcessor
import io.flowdux.Reducer
import io.flowdux.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class CounterState(val count: Int = 0) : State

sealed interface CounterAction : Action {
    object Increment : CounterAction

    object Decrement : CounterAction

    data class Add(val value: Int) : CounterAction
}

val counterReducer =
    Reducer<CounterState, CounterAction> { state, action ->
        when (action) {
            is CounterAction.Increment -> state.copy(count = state.count + 1)
            is CounterAction.Decrement -> state.copy(count = state.count - 1)
            is CounterAction.Add -> state.copy(count = state.count + action.value)
        }
    }

val testErrorProcessor =
    object : ErrorProcessor<CounterAction> {
        override fun process(throwable: Throwable): Flow<CounterAction> = emptyFlow()
    }
