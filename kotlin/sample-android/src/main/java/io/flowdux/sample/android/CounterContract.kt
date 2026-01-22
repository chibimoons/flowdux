package io.flowdux.sample.android

import io.flowdux.*

// State
data class CounterState(
    val count: Int = 0
) : State

// Actions
sealed interface CounterAction : Action {
    object Increment : CounterAction
    object Decrement : CounterAction
    object Reset : CounterAction
}

// Reducer
val counterReducer = buildReducer<CounterState, CounterAction> {
    on<CounterAction.Increment> { state, _ ->
        state.copy(count = state.count + 1)
    }
    on<CounterAction.Decrement> { state, _ ->
        state.copy(count = state.count - 1)
    }
    on<CounterAction.Reset> { _, _ ->
        CounterState()
    }
}
