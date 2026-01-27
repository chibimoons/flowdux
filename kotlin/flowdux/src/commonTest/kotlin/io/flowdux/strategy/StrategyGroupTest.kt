package io.flowdux.strategy

import app.cash.turbine.test
import io.flowdux.Middleware
import io.flowdux.createStore
import io.flowdux.debounce
import io.flowdux.retry
import io.flowdux.strategy.ExecutionStrategyTestBase.TestAction
import io.flowdux.strategy.ExecutionStrategyTestBase.TestState
import io.flowdux.strategy.ExecutionStrategyTestBase.testErrorProcessor
import io.flowdux.strategy.ExecutionStrategyTestBase.testReducer
import io.flowdux.takeLatest
import io.flowdux.takeLeading
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class StrategyGroupTest {

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

    @Test
    fun `group with retry retries failed actions within the group`() = runTest {
        val attemptCounts = mutableMapOf<String, Int>()

        val middleware = object : Middleware<TestState, TestAction> {
            override val processors = buildProcessors {
                group(retry(3)) {
                    on<TestAction.Fetch> { _, action ->
                        val key = "fetch-${action.id}"
                        val count = attemptCounts.getOrPut(key) { 0 } + 1
                        attemptCounts[key] = count
                        if (action.id == "fail" && count < 3) {
                            throw RuntimeException("Simulated failure $count")
                        }
                        emit(TestAction.FetchSuccess(action.id, "result-${action.id}"))
                    }
                    on<TestAction.Search> { _, action ->
                        val key = "search-${action.query}"
                        val count = attemptCounts.getOrPut(key) { 0 } + 1
                        attemptCounts[key] = count
                        if (action.query == "fail" && count < 2) {
                            throw RuntimeException("Simulated failure $count")
                        }
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

            // Fetch "fail" - should retry 3 times and succeed
            store.dispatch(TestAction.Fetch("fail"))
            advanceTimeBy(100)
            awaitItem()

            // Search "fail" - should retry 2 times and succeed
            store.dispatch(TestAction.Search("fail"))
            advanceTimeBy(100)
            awaitItem()

            // Fetch "ok" - should succeed first time
            store.dispatch(TestAction.Fetch("ok"))
            advanceTimeBy(100)
            awaitItem()

            // Verify retry counts
            assertEquals(3, attemptCounts["fetch-fail"]) // Fetch "fail" retried 3 times
            assertEquals(2, attemptCounts["search-fail"]) // Search "fail" retried 2 times
            assertEquals(1, attemptCounts["fetch-ok"])   // Fetch "ok" succeeded first time

            cancelAndIgnoreRemainingEvents()
        }
    }
}
