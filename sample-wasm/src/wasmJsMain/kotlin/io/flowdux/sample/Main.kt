package io.flowdux.sample

import io.flowdux.*
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

// State
data class CounterState(
    val count: Int = 0,
    val isLoading: Boolean = false
) : State

// Actions
sealed interface CounterAction : Action {
    data object Increment : CounterAction
    data object Decrement : CounterAction
    data object Reset : CounterAction
    data object IncrementAsync : CounterAction
    data class SetLoading(val loading: Boolean) : CounterAction
}

// Reducer
val counterReducer = buildReducer<CounterState, CounterAction> {
    on<CounterAction.Increment> { state, _ ->
        state.copy(count = state.count + 1)
    }
    on<CounterAction.Decrement> { state, _ ->
        state.copy(count = state.count - 1)
    }
    on<CounterAction.Reset> { state, _ ->
        state.copy(count = 0)
    }
    on<CounterAction.SetLoading> { state, action ->
        state.copy(isLoading = action.loading)
    }
}

// Middleware for async operations
class CounterMiddleware : Middleware<CounterState, CounterAction> {
    override val processors = buildProcessors {
        on<CounterAction.IncrementAsync>(takeLatest()) { _, _ ->
            emit(CounterAction.SetLoading(true))
            delay(1000) // Simulate async operation
            emit(CounterAction.Increment)
            emit(CounterAction.SetLoading(false))
        }
    }
}

// Logging middleware
class LoggingMiddleware : Middleware<CounterState, CounterAction> {
    override val processors = buildProcessors {
        on<CounterAction.Increment> { _, action ->
            addLogEntry("Action: Increment")
            emit(action)
        }
        on<CounterAction.Decrement> { _, action ->
            addLogEntry("Action: Decrement")
            emit(action)
        }
        on<CounterAction.Reset> { _, action ->
            addLogEntry("Action: Reset")
            emit(action)
        }
        on<CounterAction.IncrementAsync> { _, action ->
            addLogEntry("Action: IncrementAsync")
            emit(action)
        }
        on<CounterAction.SetLoading> { _, action ->
            addLogEntry("Action: SetLoading(${action.loading})")
            emit(action)
        }
    }
}

private fun addLogEntry(message: String) {
    val logElement = document.getElementById("log") as? HTMLElement ?: return
    val entry = document.createElement("div")
    entry.className = "log-entry"
    entry.textContent = "[${currentTime()}] $message"
    logElement.insertBefore(entry, logElement.firstChild)

    // Keep only last 20 entries
    while (logElement.childElementCount > 20) {
        logElement.lastChild?.let { logElement.removeChild(it) }
    }
}

private var logCounter = 0

private fun currentTime(): String {
    // WASM doesn't support js() the same way as JS target
    // Using a simple counter instead of actual time for the WASM sample
    return "#${++logCounter}"
}

private val scope = MainScope()

fun main() {
    val store = createStore(
        initialState = CounterState(),
        reducer = counterReducer,
        middlewares = listOf(LoggingMiddleware(), CounterMiddleware()),
        scope = scope
    )

    // DOM elements
    val counterDisplay = document.getElementById("counter") as HTMLElement
    val statusDisplay = document.getElementById("status") as HTMLElement
    val incrementBtn = document.getElementById("increment") as HTMLButtonElement
    val decrementBtn = document.getElementById("decrement") as HTMLButtonElement
    val incrementAsyncBtn = document.getElementById("incrementAsync") as HTMLButtonElement
    val resetBtn = document.getElementById("reset") as HTMLButtonElement

    // Subscribe to state changes
    scope.launch {
        store.state.collect { state ->
            counterDisplay.textContent = state.count.toString()
            statusDisplay.textContent = if (state.isLoading) "Loading..." else ""
            statusDisplay.className = if (state.isLoading) "status loading" else "status"
        }
    }

    // Button event handlers
    incrementBtn.onclick = {
        store.dispatch(CounterAction.Increment)
    }

    decrementBtn.onclick = {
        store.dispatch(CounterAction.Decrement)
    }

    incrementAsyncBtn.onclick = {
        store.dispatch(CounterAction.IncrementAsync)
    }

    resetBtn.onclick = {
        store.dispatch(CounterAction.Reset)
    }

    addLogEntry("Flowdux Store initialized (WASM)")
}
