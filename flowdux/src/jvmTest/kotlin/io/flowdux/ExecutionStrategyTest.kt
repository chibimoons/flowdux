package io.flowdux

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionStrategyTest {

    // Test-specific state and actions
    data class TestState(val values: List<String> = emptyList()) : State

    sealed interface TestAction : Action {
        data class Fetch(val id: String) : TestAction
        data class FetchSuccess(val id: String, val result: String) : TestAction
        data class Search(val query: String) : TestAction
        data class SearchResult(val query: String, val results: List<String>) : TestAction
        data class Click(val buttonId: String) : TestAction
        data class ClickProcessed(val buttonId: String) : TestAction
    }

    private val testReducer = Reducer<TestState, TestAction> { state, action ->
        when (action) {
            is TestAction.FetchSuccess -> state.copy(values = state.values + action.result)
            is TestAction.SearchResult -> state.copy(values = action.results)
            is TestAction.ClickProcessed -> state.copy(values = state.values + action.buttonId)
            else -> state
        }
    }

    private val testErrorProcessor = object : ErrorProcessor<TestAction> {
        override fun process(throwable: Throwable): Flow<TestAction> = emptyFlow()
    }

    @Nested
    inner class TakeLatestTests {

        @Test
        fun `takeLatest cancels previous execution when new action arrives`() = runTest {
            val executionOrder = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Fetch>(takeLatest()) { state, action ->
                        executionOrder.add("start-${action.id}")
                        delay(100)
                        executionOrder.add("end-${action.id}")
                        emit(TestAction.FetchSuccess(action.id, "result-${action.id}"))
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(emptyList<String>(), awaitItem().values)

                // Dispatch first action
                store.dispatch(TestAction.Fetch("1"))
                advanceTimeBy(50) // Let it start but not complete

                // Dispatch second action - should cancel first
                store.dispatch(TestAction.Fetch("2"))

                // Wait for completion
                advanceTimeBy(150)

                // Only second action should complete
                val result = awaitItem()
                assertEquals(listOf("result-2"), result.values)

                // Verify first was started but canceled
                assertTrue(executionOrder.contains("start-1"))
                assertFalse(executionOrder.contains("end-1"))
                assertTrue(executionOrder.contains("end-2"))

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `takeLatest with different keys executes independently`() = runTest {
            val completedActionsA = mutableListOf<String>()
            val completedActionsB = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    // Use separate takeLatest instances for different key groups
                    on<TestAction.Fetch>(takeLatest()) { state, action ->
                        delay(100)
                        completedActionsA.add(action.id)
                        emit(TestAction.FetchSuccess(action.id, "result-${action.id}"))
                    }
                    on<TestAction.Search>(takeLatest()) { state, action ->
                        delay(100)
                        completedActionsB.add(action.query)
                        emit(TestAction.SearchResult(action.query, listOf(action.query)))
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(emptyList<String>(), awaitItem().values)

                // Dispatch actions with different keys (Fetch vs Search)
                store.dispatch(TestAction.Fetch("f1"))
                store.dispatch(TestAction.Search("s1"))
                advanceTimeBy(50)

                // Cancel f1 with f2, but s1 should still run
                store.dispatch(TestAction.Fetch("f2"))

                advanceTimeBy(150)

                // s1 and f2 should complete (f1 canceled)
                awaitItem() // first result
                awaitItem() // second result

                assertTrue(completedActionsB.contains("s1"))
                assertTrue(completedActionsA.contains("f2"))
                assertFalse(completedActionsA.contains("f1"))

                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Nested
    inner class TakeLeadingTests {

        @Test
        fun `takeLeading ignores actions while one is processing`() = runTest {
            val executionCount = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Fetch>(takeLeading()) { state, action ->
                        executionCount.add(action.id)
                        delay(100)
                        emit(TestAction.FetchSuccess(action.id, "result-${action.id}"))
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(emptyList<String>(), awaitItem().values)

                // Dispatch multiple actions rapidly
                store.dispatch(TestAction.Fetch("1"))
                store.dispatch(TestAction.Fetch("2"))
                store.dispatch(TestAction.Fetch("3"))

                advanceTimeBy(150)

                // Only first should execute
                val result = awaitItem()
                assertEquals(listOf("result-1"), result.values)
                assertEquals(listOf("1"), executionCount)

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `takeLeading allows new action after previous completes`() = runTest {
            val executionOrder = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Click>(takeLeading()) { state, action ->
                        executionOrder.add(action.buttonId)
                        delay(50)
                        emit(TestAction.ClickProcessed(action.buttonId))
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(emptyList<String>(), awaitItem().values)

                // First click
                store.dispatch(TestAction.Click("btn1"))
                advanceTimeBy(60)
                awaitItem()

                // Second click after first completes
                store.dispatch(TestAction.Click("btn2"))
                advanceTimeBy(60)
                awaitItem()

                assertEquals(listOf("btn1", "btn2"), executionOrder)

                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Nested
    inner class DebounceTests {

        @Test
        fun `debounce delays execution and cancels on rapid actions`() = runTest {
            val searchResults = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Search>(debounce(100.milliseconds)) { state, action ->
                        searchResults.add(action.query)
                        emit(TestAction.SearchResult(action.query, listOf("result-${action.query}")))
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(emptyList<String>(), awaitItem().values)

                // Rapid typing simulation
                store.dispatch(TestAction.Search("a"))
                advanceTimeBy(30)
                store.dispatch(TestAction.Search("ab"))
                advanceTimeBy(30)
                store.dispatch(TestAction.Search("abc"))

                // Wait for debounce
                advanceTimeBy(150)

                // Only final query should execute
                val result = awaitItem()
                assertEquals(listOf("result-abc"), result.values)
                assertEquals(listOf("abc"), searchResults)

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `debounce executes after quiet period`() = runTest {
            val executedQueries = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Search>(debounce(50.milliseconds)) { state, action ->
                        executedQueries.add(action.query)
                        emit(TestAction.SearchResult(action.query, listOf(action.query)))
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(emptyList<String>(), awaitItem().values)

                // First search
                store.dispatch(TestAction.Search("first"))
                advanceTimeBy(100)
                awaitItem()

                // Second search after first completes
                store.dispatch(TestAction.Search("second"))
                advanceTimeBy(100)
                awaitItem()

                assertEquals(listOf("first", "second"), executedQueries)

                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Nested
    inner class ThrottleTests {

        @Test
        fun `throttle limits execution rate`() = runBlocking {
            // Throttle uses real time (TimeSource.Monotonic), so we use runBlocking
            val executedClicks = mutableListOf<String>()
            val storeScope = CoroutineScope(Dispatchers.Default + Job())

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Click>(throttle(100.milliseconds)) { _, action ->
                        executedClicks.add(action.buttonId)
                        emit(TestAction.ClickProcessed(action.buttonId))
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )

            try {
                store.state.test {
                    assertEquals(emptyList<String>(), awaitItem().values)

                    // First click - should execute
                    store.dispatch(TestAction.Click("1"))
                    awaitItem()

                    // Clicks within throttle window - should be ignored
                    delay(20)
                    store.dispatch(TestAction.Click("2"))
                    delay(30)
                    store.dispatch(TestAction.Click("3"))

                    // Click after throttle window - should execute
                    delay(100)
                    store.dispatch(TestAction.Click("4"))
                    awaitItem()

                    // Only first and fourth clicks should have executed
                    assertEquals(listOf("1", "4"), executedClicks)

                    cancelAndIgnoreRemainingEvents()
                }
            } finally {
                storeScope.cancel()
            }
        }

        @Test
        fun `throttle executes first action immediately`() = runBlocking {
            val executedClicks = mutableListOf<String>()
            val storeScope = CoroutineScope(Dispatchers.Default + Job())

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Click>(throttle(100.milliseconds)) { _, action ->
                        executedClicks.add(action.buttonId)
                        emit(TestAction.ClickProcessed(action.buttonId))
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )

            try {
                store.state.test {
                    assertEquals(emptyList<String>(), awaitItem().values)

                    // First click executes immediately
                    store.dispatch(TestAction.Click("btn"))
                    awaitItem()

                    // Should have executed
                    assertEquals(listOf("btn"), executedClicks)

                    cancelAndIgnoreRemainingEvents()
                }
            } finally {
                storeScope.cancel()
            }
        }
    }

    @Nested
    inner class CombinedStrategyTests {

        @Test
        fun `different strategies can be used in same middleware`() = runTest {
            val fetchExecutions = mutableListOf<String>()
            val clickExecutions = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Fetch>(takeLatest()) { state, action ->
                        delay(50)
                        fetchExecutions.add(action.id)
                        emit(TestAction.FetchSuccess(action.id, action.id))
                    }
                    on<TestAction.Click>(takeLeading()) { state, action ->
                        delay(50)
                        clickExecutions.add(action.buttonId)
                        emit(TestAction.ClickProcessed(action.buttonId))
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(emptyList<String>(), awaitItem().values)

                // Test takeLatest behavior
                store.dispatch(TestAction.Fetch("f1"))
                store.dispatch(TestAction.Fetch("f2"))
                advanceTimeBy(100)
                awaitItem()

                // Test takeLeading behavior
                store.dispatch(TestAction.Click("c1"))
                store.dispatch(TestAction.Click("c2"))
                advanceTimeBy(100)
                awaitItem()

                assertEquals(listOf("f2"), fetchExecutions) // Only latest
                assertEquals(listOf("c1"), clickExecutions) // Only leading

                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Nested
    inner class StrategyGroupTests {

        @Test
        fun `group shares strategy instance across different action types`() = runTest {
            val executionOrder = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    // Both Fetch and Search share the same takeLatest instance
                    group(takeLatest()) {
                        on<TestAction.Fetch> { _, action ->
                            executionOrder.add("fetch-start-${action.id}")
                            delay(100)
                            executionOrder.add("fetch-end-${action.id}")
                            emit(TestAction.FetchSuccess(action.id, "result-${action.id}"))
                        }
                        on<TestAction.Search> { _, action ->
                            executionOrder.add("search-start-${action.query}")
                            delay(100)
                            executionOrder.add("search-end-${action.query}")
                            emit(TestAction.SearchResult(action.query, listOf(action.query)))
                        }
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(emptyList<String>(), awaitItem().values)

                // Dispatch Fetch, then Search should cancel Fetch
                store.dispatch(TestAction.Fetch("1"))
                advanceTimeBy(50) // Let Fetch start

                store.dispatch(TestAction.Search("query"))
                advanceTimeBy(150) // Let Search complete

                // Only Search should complete
                val result = awaitItem()
                assertEquals(listOf("query"), result.values)

                // Verify Fetch was started but canceled
                assertTrue(executionOrder.contains("fetch-start-1"))
                assertFalse(executionOrder.contains("fetch-end-1"))
                assertTrue(executionOrder.contains("search-end-query"))

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `group with takeLeading blocks across different action types`() = runTest {
            val executionOrder = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    group(takeLeading()) {
                        on<TestAction.Fetch> { _, action ->
                            executionOrder.add("fetch-${action.id}")
                            delay(100)
                            emit(TestAction.FetchSuccess(action.id, action.id))
                        }
                        on<TestAction.Search> { _, action ->
                            executionOrder.add("search-${action.query}")
                            delay(100)
                            emit(TestAction.SearchResult(action.query, listOf(action.query)))
                        }
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(emptyList<String>(), awaitItem().values)

                // Dispatch Fetch, then Search should be ignored
                store.dispatch(TestAction.Fetch("1"))
                advanceTimeBy(50)
                store.dispatch(TestAction.Search("query")) // Should be ignored

                advanceTimeBy(100)
                awaitItem()

                // Only Fetch should execute
                assertEquals(listOf("fetch-1"), executionOrder)

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `separate groups have independent strategies`() = runTest {
            val groupAExecutions = mutableListOf<String>()
            val groupBExecutions = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    group(takeLatest()) {
                        on<TestAction.Fetch> { _, action ->
                            delay(50)
                            groupAExecutions.add(action.id)
                            emit(TestAction.FetchSuccess(action.id, action.id))
                        }
                    }
                    group(takeLatest()) {
                        on<TestAction.Search> { _, action ->
                            delay(50)
                            groupBExecutions.add(action.query)
                            emit(TestAction.SearchResult(action.query, listOf(action.query)))
                        }
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(emptyList<String>(), awaitItem().values)

                // Dispatch to both groups - they should not affect each other
                store.dispatch(TestAction.Fetch("f1"))
                store.dispatch(TestAction.Search("s1"))

                // Cancel within each group
                advanceTimeBy(25)
                store.dispatch(TestAction.Fetch("f2"))
                store.dispatch(TestAction.Search("s2"))

                advanceTimeBy(100)
                awaitItem()
                awaitItem()

                // Only latest from each group
                assertEquals(listOf("f2"), groupAExecutions)
                assertEquals(listOf("s2"), groupBExecutions)

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `group with debounce resets timer across different action types`() = runTest {
            val executionOrder = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    group(debounce(100.milliseconds)) {
                        on<TestAction.Fetch> { _, action ->
                            executionOrder.add("fetch-${action.id}")
                            emit(TestAction.FetchSuccess(action.id, action.id))
                        }
                        on<TestAction.Search> { _, action ->
                            executionOrder.add("search-${action.query}")
                            emit(TestAction.SearchResult(action.query, listOf(action.query)))
                        }
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(emptyList<String>(), awaitItem().values)

                // Dispatch Fetch, then Search before debounce completes
                // Search should reset the debounce timer, canceling Fetch
                store.dispatch(TestAction.Fetch("1"))
                advanceTimeBy(50)
                store.dispatch(TestAction.Search("query")) // Resets debounce

                advanceTimeBy(150) // Wait for debounce to complete
                awaitItem()

                // Only Search should execute (Fetch was canceled by debounce reset)
                assertEquals(listOf("search-query"), executionOrder)

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `group with throttle limits rate across different action types`() = runBlocking {
            val executionOrder = mutableListOf<String>()
            val storeScope = CoroutineScope(Dispatchers.Default + Job())

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    group(throttle(100.milliseconds)) {
                        on<TestAction.Fetch> { _, action ->
                            executionOrder.add("fetch-${action.id}")
                            emit(TestAction.FetchSuccess(action.id, action.id))
                        }
                        on<TestAction.Search> { _, action ->
                            executionOrder.add("search-${action.query}")
                            emit(TestAction.SearchResult(action.query, listOf(action.query)))
                        }
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )

            try {
                store.state.test {
                    assertEquals(emptyList<String>(), awaitItem().values)

                    // First action executes immediately
                    store.dispatch(TestAction.Fetch("1"))
                    awaitItem()

                    // Second action (different type) within throttle window - should be ignored
                    delay(30)
                    store.dispatch(TestAction.Search("query"))

                    // Third action after throttle window - should execute
                    delay(100)
                    store.dispatch(TestAction.Search("query2"))
                    awaitItem()

                    // Only first and third should execute
                    assertEquals(listOf("fetch-1", "search-query2"), executionOrder)

                    cancelAndIgnoreRemainingEvents()
                }
            } finally {
                storeScope.cancel()
            }
        }
    }
}
