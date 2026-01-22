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
}
