package io.flowdux

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Tests for emitting multiple FlowHolderActions from within a middleware processor.
 *
 * These tests verify that the middleware chain uses flatMapMerge (not flatMapConcat)
 * to allow concurrent processing of emitted actions. This prevents blocking when
 * a middleware emits FlowHolderActions with infinite flows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MiddlewareEmitFlowHolderActionTest {

    /**
     * Middleware that emits multiple FlowHolderActions when StartMultipleObservers is dispatched.
     * This simulates the real-world scenario where an app starts observing multiple data streams.
     */
    private class MultiObserverMiddleware : Middleware<CounterState, CounterAction> {
        override val processors: ActionProcessorMap<CounterState, CounterAction> = buildProcessors {
            on<CounterAction.StartMultipleObservers> { _, _ ->
                // Emit first infinite FlowHolderAction
                emit(CounterAction.InfiniteStreamAction("observer1", emitInterval = 50L))

                // Emit second infinite FlowHolderAction
                // With flatMapMerge, this executes concurrently (not blocked by first emit)
                emit(CounterAction.SecondaryStreamAction("observer2", emitInterval = 50L))

                // Emit a marker action to indicate setup is complete
                // With flatMapMerge, this also executes without blocking
                emit(CounterAction.SetupComplete(0L))
            }
        }
    }

    @Test
    fun `emitting multiple FlowHolderActions from middleware should not block`() = runTest {
        val store = createStore(
            initialState = CounterState(),
            middlewares = listOf(MultiObserverMiddleware()),
            reducer = counterReducer,
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        store.state.test {
            assertEquals(0, awaitItem().count)

            // Dispatch action that triggers multiple FlowHolderAction emissions
            store.dispatch(CounterAction.StartMultipleObservers)

            // We should see emissions from BOTH streams
            // InfiniteStreamAction adds 1 per emission
            // SecondaryStreamAction adds 10 per emission
            // If both are running, we should see both +1 and +10 increments

            var sawIncrementByOne = false
            var sawIncrementByTen = false
            var previousCount = 0

            // Use timeout to detect if we're stuck
            withTimeout(2000) {
                repeat(10) {
                    val currentCount = awaitItem().count
                    val increment = currentCount - previousCount

                    if (increment == 1) sawIncrementByOne = true
                    if (increment == 10) sawIncrementByTen = true

                    previousCount = currentCount

                    // Early exit if we've seen both
                    if (sawIncrementByOne && sawIncrementByTen) return@withTimeout
                }
            }

            assertTrue(
                sawIncrementByOne && sawIncrementByTen,
                "Expected both FlowHolderActions to run concurrently. " +
                    "sawIncrementByOne=$sawIncrementByOne, sawIncrementByTen=$sawIncrementByTen. " +
                    "If only sawIncrementByOne=true, the second emit was blocked."
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `code after emitting FlowHolderAction should execute`() = runTest {
        var setupCompleteReceived = false

        val testReducer = Reducer<CounterState, CounterAction> { state, action ->
            when (action) {
                is CounterAction.SetupComplete -> {
                    setupCompleteReceived = true
                    state
                }
                is CounterAction.Add -> state.copy(count = state.count + action.value)
                else -> state
            }
        }

        val store = createStore(
            initialState = CounterState(),
            middlewares = listOf(MultiObserverMiddleware()),
            reducer = testReducer,
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        store.state.test {
            assertEquals(0, awaitItem().count)

            store.dispatch(CounterAction.StartMultipleObservers)

            // Wait for some emissions and check if SetupComplete was received
            withTimeout(2000) {
                repeat(10) {
                    awaitItem()
                    if (setupCompleteReceived) return@withTimeout
                }
            }

            assertTrue(
                setupCompleteReceived,
                "SetupComplete action should have been emitted after the FlowHolderActions, " +
                    "but the code after emit() was blocked."
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun assertEquals(expected: Int, actual: Int) {
        kotlin.test.assertEquals(expected, actual)
    }
}
