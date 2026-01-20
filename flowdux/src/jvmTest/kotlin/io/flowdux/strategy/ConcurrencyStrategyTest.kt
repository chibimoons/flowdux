package io.flowdux.strategy

import app.cash.turbine.test
import io.flowdux.Middleware
import io.flowdux.createStore
import io.flowdux.strategy.ExecutionStrategyTestBase.TestAction
import io.flowdux.strategy.ExecutionStrategyTestBase.TestState
import io.flowdux.strategy.ExecutionStrategyTestBase.testErrorProcessor
import io.flowdux.strategy.ExecutionStrategyTestBase.testReducer
import io.flowdux.takeLatest
import io.flowdux.takeLeading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrencyStrategyTest {

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
}
