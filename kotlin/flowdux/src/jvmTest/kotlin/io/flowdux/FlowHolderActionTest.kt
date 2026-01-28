package io.flowdux

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowHolderActionTest {

    // ============= Basic FlowHolderAction Tests =============

    @Test
    fun `FlowHolderAction flattens inner flows and updates state`() =
        runTest {
            val valueChannel = Channel<Int>(Channel.UNLIMITED)

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.StreamConnected(valueChannel.receiveAsFlow()))

                valueChannel.send(5)
                assertEquals(5, awaitItem().count)

                valueChannel.send(3)
                assertEquals(8, awaitItem().count)

                valueChannel.close()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `FlowHolderAction with multiple flows merges all streams`() =
        runTest {
            val channel1 = Channel<Int>(Channel.UNLIMITED)
            val channel2 = Channel<Int>(Channel.UNLIMITED)

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(
                    CounterAction.MultiStreamConnected(
                        channel1.receiveAsFlow(),
                        channel2.receiveAsFlow()
                    )
                )

                channel1.send(10)
                assertEquals(10, awaitItem().count)

                channel2.send(5)
                assertEquals(15, awaitItem().count)

                channel1.send(3)
                assertEquals(18, awaitItem().count)

                channel1.close()
                channel2.close()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `FlowHolderAction completes when channel is closed`() =
        runTest {
            val valueChannel = Channel<Int>(Channel.UNLIMITED)

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.StreamConnected(valueChannel.receiveAsFlow()))

                valueChannel.send(5)
                assertEquals(5, awaitItem().count)

                valueChannel.send(10)
                assertEquals(15, awaitItem().count)

                valueChannel.close()

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `cancelable FlowHolderAction cancels previous stream when new one is dispatched`() =
        runTest {
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // Start first infinite stream
                store.dispatch(CounterAction.InfiniteStreamAction("stream1", emitInterval = 50L))

                // Wait for a few emissions
                assertEquals(1, awaitItem().count)
                assertEquals(2, awaitItem().count)

                val countBeforeNewStream = store.currentState.count

                // Start second stream - should cancel the first
                store.dispatch(CounterAction.InfiniteStreamAction("stream2", emitInterval = 50L))

                // Wait for emissions from the new stream
                awaitItem()
                awaitItem()
                awaitItem()

                // The count should only reflect emissions from one stream at a time
                // If both streams were running, count would increase much faster
                val countAfter = store.currentState.count

                // Should have roughly countBeforeNewStream + 3 emissions (not double)
                assertTrue(countAfter <= countBeforeNewStream + 5) {
                    "Expected count to be around ${countBeforeNewStream + 3}, but was $countAfter. " +
                        "Both streams might be running concurrently."
                }

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `non-cancelable FlowHolderAction allows multiple streams to run concurrently`() =
        runTest {
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // Start two non-cancelable streams
                store.dispatch(CounterAction.NonCancelableStreamAction("stream1", listOf(1, 1, 1), delayBetween = 30L))
                store.dispatch(CounterAction.NonCancelableStreamAction("stream2", listOf(10, 10, 10), delayBetween = 30L))

                // Both streams should complete and contribute to the count
                // stream1: 1 + 1 + 1 = 3
                // stream2: 10 + 10 + 10 = 30
                // Total: 33

                // Collect all emissions
                var lastCount = 0
                repeat(6) {
                    lastCount = awaitItem().count
                }

                assertEquals(33, lastCount) {
                    "Expected both streams to contribute (33), but got $lastCount"
                }

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `cancelable FlowHolderAction is cancelled when store is closed`() =
        runTest {
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // Start infinite stream
                store.dispatch(CounterAction.InfiniteStreamAction("stream1", emitInterval = 50L))

                // Wait for a few emissions
                assertEquals(1, awaitItem().count)
                assertEquals(2, awaitItem().count)

                // Close the store
                store.close()

                // Verify no new emissions occur after closing
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `other actions are processed while infinite stream is running`() =
        runTest {
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // Start infinite stream
                store.dispatch(CounterAction.InfiniteStreamAction("stream1", emitInterval = 100L))

                // Wait for first emission
                assertEquals(1, awaitItem().count)

                // Dispatch a regular action while stream is running
                store.dispatch(CounterAction.Add(100))

                // The Add action should be processed
                // We might get stream emission first or Add first, but Add should be processed
                var foundAdd = false
                repeat(5) {
                    val count = awaitItem().count
                    if (count >= 101) {
                        foundAdd = true
                    }
                }

                assertTrue(foundAdd) {
                    "Regular action should be processed while infinite stream is running"
                }

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `different cancelable FlowHolderAction types run concurrently without canceling each other`() =
        runTest {
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // Start first cancelable stream (adds 1 per emission)
                store.dispatch(CounterAction.InfiniteStreamAction("stream1", emitInterval = 50L))

                // Wait for first emission from InfiniteStreamAction
                val firstCount = awaitItem().count
                assertEquals(1, firstCount)

                // Start second cancelable stream of a DIFFERENT type (adds 10 per emission)
                store.dispatch(CounterAction.SecondaryStreamAction("stream2", emitInterval = 50L))

                // Both streams should continue running concurrently
                // We should see increments of both 1 and 10
                var sawIncrementByOne = false
                var sawIncrementByTen = false

                var previousCount = firstCount
                repeat(10) {
                    val currentCount = awaitItem().count
                    val increment = currentCount - previousCount

                    if (increment == 1) sawIncrementByOne = true
                    if (increment == 10) sawIncrementByTen = true

                    previousCount = currentCount
                }

                assertTrue(sawIncrementByOne && sawIncrementByTen) {
                    "Expected both streams to run concurrently. " +
                        "sawIncrementByOne=$sawIncrementByOne, sawIncrementByTen=$sawIncrementByTen"
                }

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ============= Delivery Tests =============

    @Test
    fun `default Dispatch delivery sends inner actions through full middleware pipeline`() =
        runTest {
            val middlewareProcessedActions = mutableListOf<Action>()

            val trackingMiddleware = object : Middleware<CounterState, CounterAction> {
                override val name = "TrackingMiddleware"
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): kotlinx.coroutines.flow.Flow<CounterAction> {
                    middlewareProcessedActions.add(action)
                    return kotlinx.coroutines.flow.flowOf(action)
                }
            }

            val valueChannel = Channel<Int>(Channel.UNLIMITED)

            val store = createStore(
                initialState = CounterState(),
                middlewares = listOf(trackingMiddleware),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // StreamConnected uses default delivery (Dispatch)
                store.dispatch(CounterAction.StreamConnected(valueChannel.receiveAsFlow()))

                valueChannel.send(5)
                assertEquals(5, awaitItem().count)

                valueChannel.send(3)
                assertEquals(8, awaitItem().count)

                valueChannel.close()

                // Inner Add actions should pass through the tracking middleware
                val innerActionsInMiddleware = middlewareProcessedActions.filterIsInstance<CounterAction.Add>()
                assertTrue(
                    innerActionsInMiddleware.isNotEmpty(),
                    "Default Dispatch delivery should send inner actions through middlewares"
                )
                assertEquals(
                    listOf(CounterAction.Add(5), CounterAction.Add(3)),
                    innerActionsInMiddleware,
                    "Expected Add(5) and Add(3) to pass through middleware"
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `explicit Emit delivery bypasses user middlewares`() =
        runTest {
            val middlewareProcessedActions = mutableListOf<Action>()

            val trackingMiddleware = object : Middleware<CounterState, CounterAction> {
                override val name = "TrackingMiddleware"
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): kotlinx.coroutines.flow.Flow<CounterAction> {
                    middlewareProcessedActions.add(action)
                    return kotlinx.coroutines.flow.flowOf(action)
                }
            }

            val valueChannel = Channel<Int>(Channel.UNLIMITED)

            val store = createStore(
                initialState = CounterState(),
                middlewares = listOf(trackingMiddleware),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // EmitDeliveryStreamAction uses explicit Emit delivery
                store.dispatch(CounterAction.EmitDeliveryStreamAction(valueChannel.receiveAsFlow()))

                valueChannel.send(5)
                assertEquals(5, awaitItem().count)

                valueChannel.send(3)
                assertEquals(8, awaitItem().count)

                valueChannel.close()

                // EmitDeliveryStreamAction itself passes through the middleware,
                // but inner Add actions should NOT appear in the tracking middleware
                val innerActionsInMiddleware = middlewareProcessedActions.filterIsInstance<CounterAction.Add>()
                assertTrue(
                    innerActionsInMiddleware.isEmpty(),
                    "Emit delivery should bypass user middlewares, but found inner actions: $innerActionsInMiddleware"
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ============= Strategy-based Tests =============

    @Test
    fun `FlowHolderAction with TakeLeading strategy ignores subsequent dispatches`() =
        runTest {
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // Start first TakeLeading stream (values: 1, 1, 1)
                store.dispatch(CounterAction.TakeLeadingStreamAction("stream1", listOf(1, 1, 1), delayBetween = 50L))

                // Immediately dispatch second stream (values: 10, 10, 10) - should be ignored
                store.dispatch(CounterAction.TakeLeadingStreamAction("stream2", listOf(10, 10, 10), delayBetween = 50L))

                // Only first stream should run
                // stream1: 1 + 1 + 1 = 3
                assertEquals(1, awaitItem().count)
                assertEquals(2, awaitItem().count)
                assertEquals(3, awaitItem().count)

                // Verify second stream was ignored - no more emissions
                expectNoEvents()

                assertEquals(3, store.currentState.count)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `FlowHolderAction with Debounce strategy delays execution`() =
        runTest {
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // Dispatch multiple debounced actions rapidly
                store.dispatch(CounterAction.DebouncedStreamAction("a1", value = 1, debounceMs = 100L))
                delay(30)
                store.dispatch(CounterAction.DebouncedStreamAction("a2", value = 2, debounceMs = 100L))
                delay(30)
                store.dispatch(CounterAction.DebouncedStreamAction("a3", value = 3, debounceMs = 100L))

                // Only the last one should execute after debounce
                // Wait for debounce to complete
                delay(150)

                val finalCount = awaitItem().count
                assertEquals(3, finalCount) {
                    "Expected only last debounced action (3) to execute, but got $finalCount"
                }

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `FlowHolderAction with Throttle strategy limits execution rate`() =
        runTest {
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // Dispatch first throttled action - should execute immediately
                store.dispatch(CounterAction.ThrottledStreamAction("a1", value = 1, throttleMs = 200L))
                assertEquals(1, awaitItem().count)

                // Dispatch more actions within throttle window - should be ignored
                store.dispatch(CounterAction.ThrottledStreamAction("a2", value = 10, throttleMs = 200L))
                store.dispatch(CounterAction.ThrottledStreamAction("a3", value = 100, throttleMs = 200L))

                // Verify no new emissions from throttled actions
                expectNoEvents()

                assertEquals(1, store.currentState.count) {
                    "Expected actions within throttle window to be ignored"
                }

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ============= Nested FlowHolderAction Tests =============

    @Test
    fun `nested FlowHolderAction processes recursively`() =
        runTest {
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // Create an inner FlowHolderAction that adds 1, 2, 3
                val innerAction = CounterAction.NonCancelableStreamAction(
                    id = "inner",
                    values = listOf(1, 2, 3),
                    delayBetween = 10L
                )

                // Dispatch nested FlowHolderAction
                // It will emit Add(100) first, then emit the innerAction
                store.dispatch(CounterAction.NestedFlowHolderAction(innerAction))

                // First: Add(100) from outer action
                assertEquals(100, awaitItem().count)

                // Then: 1, 2, 3 from inner action
                assertEquals(101, awaitItem().count)
                assertEquals(103, awaitItem().count)
                assertEquals(106, awaitItem().count)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `deeply nested FlowHolderActions process correctly`() =
        runTest {
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // Level 3: innermost action emits 1
                val level3 = CounterAction.NonCancelableStreamAction(
                    id = "level3",
                    values = listOf(1),
                    delayBetween = 10L
                )

                // Level 2: wraps level3, also emits Add(100)
                val level2 = CounterAction.NestedFlowHolderAction(level3)

                // Level 1: wraps level2, also emits Add(100)
                val level1 = CounterAction.NestedFlowHolderAction(level2)

                // Dispatch the outermost nested action
                store.dispatch(level1)

                // level1 emits Add(100), then level2
                // level2 emits Add(100), then level3
                // level3 emits Add(1)
                // Total: 100 + 100 + 1 = 201

                assertEquals(100, awaitItem().count)  // From level1
                assertEquals(200, awaitItem().count)  // From level2
                assertEquals(201, awaitItem().count)  // From level3

                cancelAndIgnoreRemainingEvents()
            }
        }
}
