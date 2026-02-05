package io.flowdux.strategy

import app.cash.turbine.test
import io.flowdux.Middleware
import io.flowdux.createStore
import io.flowdux.debounce
import io.flowdux.strategy.ExecutionStrategyTestBase.TestAction
import io.flowdux.strategy.ExecutionStrategyTestBase.TestState
import io.flowdux.strategy.ExecutionStrategyTestBase.testErrorProcessor
import io.flowdux.strategy.ExecutionStrategyTestBase.testReducer
import io.flowdux.throttle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TimingStrategyTest {

    class DebounceTests {

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

    class ThrottleTests {

        @Test
        fun `throttle limits execution rate`() = runBlocking {
            // Throttle uses real time (TimeSource.Monotonic), so we use runBlocking.
            // Large timing margins are needed for CI (especially iosSimulatorArm64).
            val executedClicks = mutableListOf<String>()
            val storeScope = CoroutineScope(Dispatchers.Default + Job())

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Click>(throttle(500.milliseconds)) { _, action ->
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
                    delay(100)
                    store.dispatch(TestAction.Click("2"))
                    delay(100)
                    store.dispatch(TestAction.Click("3"))

                    // Click after throttle window - should execute
                    delay(800)
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
}
