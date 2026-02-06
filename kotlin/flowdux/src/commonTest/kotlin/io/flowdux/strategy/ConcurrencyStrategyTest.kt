package io.flowdux.strategy

import app.cash.turbine.test
import io.flowdux.Middleware
import io.flowdux.createStore
import io.flowdux.strategy.ExecutionStrategyTestBase.TestAction
import io.flowdux.strategy.ExecutionStrategyTestBase.TestState
import io.flowdux.strategy.ExecutionStrategyTestBase.testErrorProcessor
import io.flowdux.strategy.ExecutionStrategyTestBase.testReducer
import io.flowdux.sequential
import io.flowdux.takeLatest
import io.flowdux.takeLeading
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

@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrencyStrategyTest {

    class TakeLatestTests {

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

    class TakeLeadingTests {

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

    class SequentialTests {

        @Test
        fun `sequential processes all actions in order`() = runTest {
            val executionOrder = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Fetch>(sequential()) { _, action ->
                        executionOrder.add("start-${action.id}")
                        delay(50)
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

                // Dispatch multiple actions rapidly
                store.dispatch(TestAction.Fetch("1"))
                store.dispatch(TestAction.Fetch("2"))
                store.dispatch(TestAction.Fetch("3"))

                // Wait for all to complete (3 * 50ms = 150ms + buffer)
                advanceTimeBy(200)

                // All actions should complete in order
                awaitItem() // result-1
                awaitItem() // result-2
                awaitItem() // result-3

                // Verify order: each action should start and end before the next starts
                assertEquals(
                    listOf("start-1", "end-1", "start-2", "end-2", "start-3", "end-3"),
                    executionOrder
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `sequential differs from takeLeading by processing all actions`() = runTest {
            val sequentialExecutions = mutableListOf<String>()
            val leadingExecutions = mutableListOf<String>()

            val sequentialMiddleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Fetch>(sequential()) { _, action ->
                        sequentialExecutions.add(action.id)
                        delay(50)
                        emit(TestAction.FetchSuccess(action.id, action.id))
                    }
                }
            }

            val leadingMiddleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Search>(takeLeading()) { _, action ->
                        leadingExecutions.add(action.query)
                        delay(50)
                        emit(TestAction.SearchResult(action.query, listOf(action.query)))
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(sequentialMiddleware, leadingMiddleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(emptyList<String>(), awaitItem().values)

                // Dispatch to both middlewares
                store.dispatch(TestAction.Fetch("1"))
                store.dispatch(TestAction.Fetch("2"))
                store.dispatch(TestAction.Fetch("3"))
                store.dispatch(TestAction.Search("a"))
                store.dispatch(TestAction.Search("b"))
                store.dispatch(TestAction.Search("c"))

                advanceTimeBy(200)

                // Sequential: all 3 processed
                // Leading: only first processed (others ignored)
                awaitItem()
                awaitItem()
                awaitItem()
                awaitItem()

                assertEquals(listOf("1", "2", "3"), sequentialExecutions)
                assertEquals(listOf("a"), leadingExecutions)

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `sequential with group processes different action types in order`() = runTest {
            val executionOrder = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    group(sequential()) {
                        on<TestAction.Fetch> { _, action ->
                            executionOrder.add("fetch-start-${action.id}")
                            delay(50)
                            executionOrder.add("fetch-end-${action.id}")
                            emit(TestAction.FetchSuccess(action.id, action.id))
                        }
                        on<TestAction.Search> { _, action ->
                            executionOrder.add("search-start-${action.query}")
                            delay(50)
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

                // Dispatch mixed action types
                store.dispatch(TestAction.Fetch("1"))
                store.dispatch(TestAction.Search("a"))
                store.dispatch(TestAction.Fetch("2"))

                advanceTimeBy(200)

                awaitItem()
                awaitItem()
                awaitItem()

                // All actions should process sequentially across types
                assertEquals(
                    listOf(
                        "fetch-start-1", "fetch-end-1",
                        "search-start-a", "search-end-a",
                        "fetch-start-2", "fetch-end-2"
                    ),
                    executionOrder
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `sequential waits for long-running first action before processing next`() = runTest {
            val executionOrder = mutableListOf<String>()
            val timestamps = mutableListOf<Pair<String, Long>>()
            val startTime = testScheduler.currentTime

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Fetch>(sequential()) { _, action ->
                        val eventName = "start-${action.id}"
                        executionOrder.add(eventName)
                        timestamps.add(eventName to (testScheduler.currentTime - startTime))

                        // First action takes much longer
                        val processingTime = if (action.id == "1") 300L else 50L
                        delay(processingTime)

                        val endName = "end-${action.id}"
                        executionOrder.add(endName)
                        timestamps.add(endName to (testScheduler.currentTime - startTime))
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

                // Dispatch all actions at once
                store.dispatch(TestAction.Fetch("1")) // 300ms
                store.dispatch(TestAction.Fetch("2")) // 50ms
                store.dispatch(TestAction.Fetch("3")) // 50ms

                // Wait for all to complete (300 + 50 + 50 = 400ms + buffer)
                advanceTimeBy(450)

                awaitItem() // result-1
                awaitItem() // result-2
                awaitItem() // result-3

                // Verify strict sequential order
                assertEquals(
                    listOf("start-1", "end-1", "start-2", "end-2", "start-3", "end-3"),
                    executionOrder
                )

                // Verify timing: action 2 should not start until action 1 ends at 300ms
                val start2Time = timestamps.find { it.first == "start-2" }!!.second
                assertTrue(start2Time >= 300, "Action 2 should start after action 1 ends (300ms), but started at $start2Time")

                // Action 3 should start after action 2 ends (300 + 50 = 350ms)
                val start3Time = timestamps.find { it.first == "start-3" }!!.second
                assertTrue(start3Time >= 350, "Action 3 should start after action 2 ends (350ms), but started at $start3Time")

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `sequential continues processing after one action fails`() = runTest {
            val executionOrder = mutableListOf<String>()
            var errorCount = 0

            val errorProcessor = object : io.flowdux.ErrorProcessor<TestAction> {
                override fun process(throwable: Throwable): kotlinx.coroutines.flow.Flow<TestAction> {
                    errorCount++
                    return kotlinx.coroutines.flow.emptyFlow()
                }
            }

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Fetch>(sequential()) { _, action ->
                        executionOrder.add("start-${action.id}")
                        if (action.id == "2") {
                            throw RuntimeException("Action 2 fails")
                        }
                        delay(50)
                        executionOrder.add("end-${action.id}")
                        emit(TestAction.FetchSuccess(action.id, "result-${action.id}"))
                    }
                }
            }

            val store = createStore(
                initialState = TestState(),
                reducer = testReducer,
                middlewares = listOf(middleware),
                errorProcessor = errorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(emptyList<String>(), awaitItem().values)

                // Dispatch 3 actions - action 2 will fail
                store.dispatch(TestAction.Fetch("1"))
                store.dispatch(TestAction.Fetch("2")) // This will fail
                store.dispatch(TestAction.Fetch("3"))

                advanceTimeBy(200)

                // Actions 1 and 3 should complete
                awaitItem() // result-1
                awaitItem() // result-3

                // Verify order: action 2 started but failed, then action 3 executed
                assertEquals(
                    listOf("start-1", "end-1", "start-2", "start-3", "end-3"),
                    executionOrder
                )
                assertEquals(1, errorCount)

                cancelAndIgnoreRemainingEvents()
            }
        }

    }

    class RealDispatcherTests {

        @Test
        fun `takeLatest cancels previous execution with real dispatcher`() = runBlocking {
            // Large timing margins are needed for CI (especially iosSimulatorArm64).
            val executionOrder = mutableListOf<String>()
            val storeScope = CoroutineScope(Dispatchers.Default + Job())

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Fetch>(takeLatest()) { _, action ->
                        executionOrder.add("start-${action.id}")
                        delay(500)
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
                scope = storeScope,
            )

            try {
                store.state.test {
                    assertEquals(emptyList<String>(), awaitItem().values)

                    // Dispatch first action
                    store.dispatch(TestAction.Fetch("1"))
                    delay(200) // Let it start but not complete

                    // Dispatch second action - should cancel first
                    store.dispatch(TestAction.Fetch("2"))

                    // Only second action should complete
                    val result = awaitItem()
                    assertEquals(listOf("result-2"), result.values)

                    // Give some time for any pending operations
                    delay(300)

                    // Verify first was started but canceled
                    assertTrue(executionOrder.contains("start-1"))
                    assertFalse(executionOrder.contains("end-1"))
                    assertTrue(executionOrder.contains("end-2"))

                    cancelAndIgnoreRemainingEvents()
                }
            } finally {
                storeScope.cancel()
            }
        }
    }
}
