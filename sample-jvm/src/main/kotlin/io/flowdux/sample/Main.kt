package io.flowdux.sample

import io.flowdux.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

// State
data class CounterState(
    val count: Int = 0,
    val source: String = ""
) : State

// Simulated Repository that emits cached data first, then fresh API data
object CounterRepository {
    fun getCount(): Flow<Pair<Int, String>> = flow {
        emit(10 to "cache")   // First: cached data
        delay(500)            // Simulate network delay
        emit(42 to "api")     // Then: fresh API response
    }
}

// Actions
sealed interface CounterAction : Action {
    object Increment : CounterAction
    object Decrement : CounterAction
    data class Add(val value: Int) : CounterAction
    object Reset : CounterAction
    data class SetCount(val value: Int, val source: String) : CounterAction

    // FlowHolderAction: holds and converts existing Flow to Flow<Action>
    // No side effects - just wraps the flow from Repository/Socket
    data class ObserveCount(
        private val countFlow: Flow<Pair<Int, String>>
    ) : CounterAction, FlowHolderAction {
        override fun toFlowAction(): Flow<Action> =
            countFlow.map { (value, source) -> SetCount(value, source) }
    }
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
    on<CounterAction.SetCount> { state, action ->
        state.copy(count = action.value, source = action.source)
    }
}

fun main() {
    println("=== Flowdux Sample: Counter ===\n")

    val scope = CoroutineScope(Dispatchers.Default)

    val store = createStore(
        initialState = CounterState(),
        reducer = counterReducer,
        scope = scope
    )

    // Collect state changes in background
    scope.launch {
        store.state.collect { state ->
            val sourceInfo = if (state.source.isNotEmpty()) " [${state.source}]" else ""
            println("State: count = ${state.count}$sourceInfo")
        }
    }

    runBlocking {
        // Give collector time to start
        delay(100)

        println("\n> Dispatching Increment")
        store.dispatch(CounterAction.Increment)
        delay(100)

        println("\n> Dispatching Increment")
        store.dispatch(CounterAction.Increment)
        delay(100)

        // FlowHolderAction: wraps existing Flow and converts to Actions
        // The Flow comes from Repository (side effect happens there, not in Action)
        println("\n> Dispatching ObserveCount - FlowHolderAction")
        println("  (Repository Flow emits: cache -> api)")
        val repositoryFlow = CounterRepository.getCount()
        store.dispatch(CounterAction.ObserveCount(repositoryFlow))
        delay(700) // Wait for both emissions

        println("\n> Dispatching Add(10)")
        store.dispatch(CounterAction.Add(10))
        delay(100)

        println("\n> Dispatching Reset")
        store.dispatch(CounterAction.Reset)
        delay(100)

        println("\n=== Done ===")
    }

    scope.cancel()
}
