package io.flowdux.sample

import io.flowdux.*
import io.flowdux.timetravel.createTimeTravelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.milliseconds

// State
data class CounterState(
    val count: Int = 0,
    val source: String = "",
    val searchResults: List<String> = emptyList(),
    val isLoading: Boolean = false
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

    // Execution Strategy Examples
    data class Search(val query: String) : CounterAction
    data class SearchResult(val results: List<String>) : CounterAction
    data class FetchData(val id: String) : CounterAction
    data class FetchSuccess(val id: String, val value: Int) : CounterAction
    object SubmitForm : CounterAction
    object SubmitSuccess : CounterAction

    // Strategy Group Examples
    data class LoadUser(val userId: String) : CounterAction
    object RefreshUser : CounterAction
    data class UserLoaded(val userName: String) : CounterAction
}

// Simulated Search API
object SearchApi {
    suspend fun search(query: String): List<String> {
        delay(300) // Simulate network delay
        return listOf("$query-result-1", "$query-result-2", "$query-result-3")
    }
}

// Middleware with Execution Strategies
class ExecutionStrategyMiddleware : Middleware<CounterState, CounterAction> {
    override val processors = buildProcessors {
        // takeLatest: Only the latest search executes, previous ones are canceled
        on<CounterAction.Search>(takeLatest("search")) { _, action ->
            println("    [takeLatest] Searching for: ${action.query}")
            val results = SearchApi.search(action.query)
            println("    [takeLatest] Search completed: ${action.query}")
            emit(CounterAction.SearchResult(results))
        }

        // debounce: Wait 200ms of no input before executing
        on<CounterAction.FetchData>(debounce(200.milliseconds)) { _, action ->
            println("    [debounce] Fetching data: ${action.id}")
            delay(100)
            emit(CounterAction.FetchSuccess(action.id, 42))
        }

        // takeLeading: Ignore subsequent submissions while one is processing
        on<CounterAction.SubmitForm>(takeLeading("submit")) { _, _ ->
            println("    [takeLeading] Processing form submission...")
            delay(500) // Simulate slow API
            println("    [takeLeading] Form submitted!")
            emit(CounterAction.SubmitSuccess)
        }

        // Strategy Group: Different action types share the same strategy instance
        // LoadUser and RefreshUser will cancel each other
        group(takeLatest("user-data")) {
            on<CounterAction.LoadUser> { _, action ->
                println("    [group] Loading user: ${action.userId}")
                delay(300) // Simulate API call
                println("    [group] User loaded: ${action.userId}")
                emit(CounterAction.UserLoaded("User-${action.userId}"))
            }
            on<CounterAction.RefreshUser> { _, _ ->
                println("    [group] Refreshing user...")
                delay(300) // Simulate API call
                println("    [group] User refreshed!")
                emit(CounterAction.UserLoaded("RefreshedUser"))
            }
        }
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
    on<CounterAction.SearchResult> { state, action ->
        state.copy(searchResults = action.results)
    }
    on<CounterAction.FetchSuccess> { state, action ->
        state.copy(count = action.value, source = "fetch-${action.id}")
    }
    on<CounterAction.SubmitSuccess> { state, _ ->
        state.copy(source = "submitted")
    }
    on<CounterAction.UserLoaded> { state, action ->
        state.copy(source = action.userName)
    }
}

fun main() {
    println("=== Flowdux Sample: Counter ===\n")

    val scope = CoroutineScope(Dispatchers.Default)

    val store = createStore(
        initialState = CounterState(),
        reducer = counterReducer,
        middlewares = listOf(ExecutionStrategyMiddleware()),
        scope = scope
    )

    // Collect state changes in background
    scope.launch {
        store.state.collect { state ->
            val sourceInfo = if (state.source.isNotEmpty()) " [${state.source}]" else ""
            val searchInfo = if (state.searchResults.isNotEmpty()) " results=${state.searchResults}" else ""
            println("State: count = ${state.count}$sourceInfo$searchInfo")
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

        // ==================== Execution Strategy Examples ====================

        println("\n" + "=".repeat(50))
        println("=== Execution Strategy Examples ===")
        println("=".repeat(50))

        // takeLatest Example: Rapid search - only latest completes
        println("\n> takeLatest: Rapid search (only latest completes)")
        println("  Dispatching Search('a'), Search('ab'), Search('abc') rapidly...")
        store.dispatch(CounterAction.Search("a"))
        delay(50)
        store.dispatch(CounterAction.Search("ab"))
        delay(50)
        store.dispatch(CounterAction.Search("abc"))
        delay(500) // Wait for final search to complete
        println("  Result: Only 'abc' search completed!")

        // debounce Example: Wait for typing to stop
        println("\n> debounce: Wait 200ms after last input")
        println("  Dispatching FetchData rapidly...")
        store.dispatch(CounterAction.FetchData("1"))
        delay(50)
        store.dispatch(CounterAction.FetchData("2"))
        delay(50)
        store.dispatch(CounterAction.FetchData("3"))
        delay(400) // Wait for debounce + fetch
        println("  Result: Only last FetchData executed after 200ms quiet period!")

        // takeLeading Example: Prevent double submission
        println("\n> takeLeading: Prevent double form submission")
        println("  Dispatching SubmitForm 3 times rapidly...")
        store.dispatch(CounterAction.SubmitForm)
        delay(50)
        store.dispatch(CounterAction.SubmitForm) // ignored
        delay(50)
        store.dispatch(CounterAction.SubmitForm) // ignored
        delay(600) // Wait for submission to complete
        println("  Result: Only first submission processed, others ignored!")

        // Strategy Group Example: Different action types share same strategy
        println("\n> Strategy Group: LoadUser and RefreshUser share takeLatest")
        println("  Dispatching LoadUser, then RefreshUser (cancels LoadUser)...")
        store.dispatch(CounterAction.LoadUser("123"))
        delay(100) // Let LoadUser start
        store.dispatch(CounterAction.RefreshUser) // Cancels LoadUser
        delay(400) // Wait for RefreshUser to complete
        println("  Result: LoadUser was canceled, only RefreshUser completed!")

        // ==================== Time Travel Debugging ====================

        println("\n" + "=".repeat(50))
        println("=== Time Travel Debugging ===")
        println("=".repeat(50))

        val timeTravelScope = CoroutineScope(Dispatchers.Default)
        val timeTravelStore = createTimeTravelStore(
            initialState = CounterState(),
            reducer = counterReducer,
            scope = timeTravelScope
        )

        // Collect state changes
        val stateJob = timeTravelScope.launch {
            timeTravelStore.state.collect { state ->
                println("  State: count = ${state.count}")
            }
        }
        delay(50)

        println("\n> Building history with dispatches...")
        timeTravelStore.dispatch(CounterAction.Increment)
        delay(50)
        timeTravelStore.dispatch(CounterAction.Increment)
        delay(50)
        timeTravelStore.dispatch(CounterAction.Add(10))
        delay(50)

        println("\n> Current history: ${timeTravelStore.history.map { it.currentState.count }}")
        println("  (index ${timeTravelStore.currentIndex} of ${timeTravelStore.history.size - 1})")

        println("\n> Undo (go back one step)")
        timeTravelStore.undo()
        delay(50)
        println("  Now at index ${timeTravelStore.currentIndex}, canUndo=${timeTravelStore.canUndo}, canRedo=${timeTravelStore.canRedo}")

        println("\n> Undo again")
        timeTravelStore.undo()
        delay(50)
        println("  Now at index ${timeTravelStore.currentIndex}")

        println("\n> Redo (go forward one step)")
        timeTravelStore.redo()
        delay(50)
        println("  Now at index ${timeTravelStore.currentIndex}")

        println("\n> JumpTo(0) - go to initial state")
        timeTravelStore.jumpTo(0)
        delay(50)
        println("  Now at index ${timeTravelStore.currentIndex}")

        println("\n> JumpTo(3) - go to final state")
        timeTravelStore.jumpTo(3)
        delay(50)
        println("  Now at index ${timeTravelStore.currentIndex}")

        println("\n> Dispatch from past state (creates new branch)")
        timeTravelStore.jumpTo(1)
        delay(50)
        println("  Jumped to index 1 (count=1)")
        println("  History before: ${timeTravelStore.history.map { it.currentState.count }}")
        timeTravelStore.dispatch(CounterAction.Add(100))
        delay(50)
        println("  History after:  ${timeTravelStore.history.map { it.currentState.count }}")
        println("  Future states [2, 12] were discarded!")

        stateJob.cancel()
        timeTravelStore.close()

        println("\n" + "=".repeat(50))
        println("=== Done ===")
    }

    scope.cancel()
}
