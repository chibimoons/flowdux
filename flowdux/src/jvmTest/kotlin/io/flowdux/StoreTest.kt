package io.flowdux

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Core store functionality tests.
 * For other test categories, see:
 * - MiddlewareTest.kt - Middleware chain and interception tests
 * - FlowHolderActionTest.kt - FlowHolderAction streaming tests
 * - IOTest.kt - IO simulation and delay tests
 * - ErrorHandlingTest.kt - Error processor tests
 * - ScopeAndLifecycleTest.kt - Scope cancellation and store close tests
 * - ConcurrencyTest.kt - Concurrent dispatch and race condition tests
 * - ExecutionStrategyTest.kt - Execution strategy (takeLatest, debounce, etc.) tests
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreTest {

    @Test
    fun `store initializes with initial state`() =
        runTest {
            val store = createStore(
                initialState = CounterState(count = 5),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            assertEquals(5, store.currentState.count)
        }

    @Test
    fun `dispatch increment action updates state`() =
        runTest {
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.Increment)
                assertEquals(1, awaitItem().count)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `dispatch multiple actions updates state correctly`() =
        runTest {
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.Increment)
                assertEquals(1, awaitItem().count)

                store.dispatch(CounterAction.Increment)
                assertEquals(2, awaitItem().count)

                store.dispatch(CounterAction.Decrement)
                assertEquals(1, awaitItem().count)

                store.dispatch(CounterAction.Add(10))
                assertEquals(11, awaitItem().count)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `state flow emits updates`() =
        runTest {
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.Increment)
                assertEquals(1, awaitItem().count)

                store.dispatch(CounterAction.Add(5))
                assertEquals(6, awaitItem().count)

                cancelAndIgnoreRemainingEvents()
            }
        }
}
