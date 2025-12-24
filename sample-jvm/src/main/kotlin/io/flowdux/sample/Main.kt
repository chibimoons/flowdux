package io.flowdux.sample

import io.flowdux.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

// State
data class CounterState(val count: Int = 0) : State

// Actions
sealed interface CounterAction : Action {
    object Increment : CounterAction
    object Decrement : CounterAction
    data class Add(val value: Int) : CounterAction
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
    on<CounterAction.Add> { state, action ->
        state.copy(count = state.count + action.value)
    }
    on<CounterAction.Reset> { _, _ ->
        CounterState()
    }
}

fun main() = runBlocking {
    println("=== Flowdux Sample: Counter ===\n")

    val store = createStore(
        initialState = CounterState(),
        reducer = counterReducer,
        scope = this
    )

    // Collect state changes in background
    val job = launch {
        store.state.collect { state ->
            println("State: count = ${state.count}")
        }
    }

    // Give collector time to start
    delay(100)

    println("\n> Dispatching Increment")
    store.dispatch(CounterAction.Increment)
    delay(100)

    println("\n> Dispatching Increment")
    store.dispatch(CounterAction.Increment)
    delay(100)

    println("\n> Dispatching Add(10)")
    store.dispatch(CounterAction.Add(10))
    delay(100)

    println("\n> Dispatching Decrement")
    store.dispatch(CounterAction.Decrement)
    delay(100)

    println("\n> Dispatching Reset")
    store.dispatch(CounterAction.Reset)
    delay(100)

    println("\n=== Final State: count = ${store.currentState.count} ===")

    job.cancel()
    store.cancel()
}
