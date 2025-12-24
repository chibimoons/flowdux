package io.flowdux.sample.shared

import io.flowdux.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

// Closeable interface for iOS
interface Closeable {
    fun close()
}

// Store wrapper for easy platform usage
class CounterStore(private val scope: CoroutineScope) {
    private val store = createStore(
        initialState = CounterState(),
        reducer = counterReducer,
        scope = scope
    )

    val state: StateFlow<CounterState> = store.state
    val currentState: CounterState get() = store.currentState

    fun increment() = store.dispatch(CounterAction.Increment)
    fun decrement() = store.dispatch(CounterAction.Decrement)
    fun add(value: Int) = store.dispatch(CounterAction.Add(value))
    fun reset() = store.dispatch(CounterAction.Reset)

    // For iOS: observe state changes
    fun watchState(onChange: (CounterState) -> Unit): Closeable {
        val job = scope.launch {
            state.collect { onChange(it) }
        }
        return object : Closeable {
            override fun close() {
                job.cancel()
            }
        }
    }
}
