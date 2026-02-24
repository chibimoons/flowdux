package io.flowdux

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScopeAndLifecycleTest {

    // ==================== Scope Cancellation Tests ====================

    @Test
    fun `scope cancellation does not affect external channel producer`() =
        runTest {
            val externalChannel = Channel<Int>(Channel.UNLIMITED)
            val storeScope = CoroutineScope(coroutineContext + Job())

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.StreamConnected(externalChannel.receiveAsFlow()))

                externalChannel.send(5)
                assertEquals(5, awaitItem().count)

                externalChannel.send(3)
                assertEquals(8, awaitItem().count)

                cancelAndIgnoreRemainingEvents()
            }

            storeScope.cancel()

            assertFalse(externalChannel.isClosedForSend)
            assertFalse(externalChannel.isClosedForReceive)

            externalChannel.send(100)

            externalChannel.close()
        }

    @Test
    fun `scope cancel stops collecting from FlowHolderAction`() =
        runTest {
            val externalChannel = Channel<Int>(Channel.UNLIMITED)
            val receivedValues = mutableListOf<Int>()
            val storeScope = CoroutineScope(coroutineContext + Job())

            val trackingReducer = Reducer<CounterState, CounterAction> { state, action ->
                when (action) {
                    is CounterAction.Add -> {
                        receivedValues.add(action.value)
                        state.copy(count = state.count + action.value)
                    }
                    else -> counterReducer.reduce(state, action)
                }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = trackingReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.StreamConnected(externalChannel.receiveAsFlow()))

                externalChannel.send(10)
                assertEquals(10, awaitItem().count)

                externalChannel.send(20)
                assertEquals(30, awaitItem().count)

                assertEquals(listOf(10, 20), receivedValues)

                cancelAndIgnoreRemainingEvents()
            }

            val valuesBefore = receivedValues.toList()

            storeScope.cancel()

            externalChannel.send(100)
            externalChannel.send(200)
            advanceUntilIdle()

            assertEquals(valuesBefore, receivedValues)

            externalChannel.close()
        }

    // ==================== Store Close Tests ====================

    @Test
    fun `close cancels scope and stops processing actions`() =
        runTest {
            val storeScope = CoroutineScope(coroutineContext + Job())
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.Increment)
                assertEquals(1, awaitItem().count)

                store.close()

                // After close, dispatch should not update state
                store.dispatch(CounterAction.Increment)
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `close stops FlowHolderAction stream collection`() =
        runTest {
            val storeScope = CoroutineScope(coroutineContext + Job())
            val valueChannel = Channel<Int>(Channel.UNLIMITED)
            val receivedValues = mutableListOf<Int>()

            val trackingReducer = Reducer<CounterState, CounterAction> { state, action ->
                when (action) {
                    is CounterAction.Add -> {
                        receivedValues.add(action.value)
                        state.copy(count = state.count + action.value)
                    }
                    else -> counterReducer.reduce(state, action)
                }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = trackingReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.StreamConnected(valueChannel.receiveAsFlow()))

                valueChannel.send(10)
                assertEquals(10, awaitItem().count)

                store.close()

                valueChannel.send(20)
                valueChannel.send(30)
                advanceUntilIdle()

                // Values after close should not be received
                assertEquals(listOf(10), receivedValues)

                cancelAndIgnoreRemainingEvents()
            }

            valueChannel.close()
        }

    @Test
    fun `close can be called multiple times safely`() =
        runTest {
            val storeScope = CoroutineScope(coroutineContext + Job())
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )

            store.close()
            store.close() // Should not throw
        }

    @Test
    fun `isClosed returns false before close and true after close`() =
        runTest {
            val storeScope = CoroutineScope(coroutineContext + Job())
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )

            assertFalse(store.isClosed)

            store.close()

            assertTrue(store.isClosed)
        }

    @Test
    fun `dispatch after close calls onDispatchAfterClose logger`() =
        runTest {
            val storeScope = CoroutineScope(coroutineContext + Job())
            val dispatchedAfterClose = mutableListOf<CounterAction>()

            val testLogger = object : StoreLogger<CounterState, CounterAction> {
                override fun onActionDispatched(action: CounterAction) {}
                override fun onMiddlewareProcessing(middlewareName: String, action: CounterAction) {}
                override fun onMiddlewaresCompleted(action: CounterAction) {}
                override fun onFlowHolderActionEmitted(action: CounterAction) {}
                override fun onErrorOccurred(throwable: Throwable) {}
                override fun onErrorHandled(action: CounterAction) {}
                override fun onStateReduced(action: CounterAction, previousState: CounterState, newState: CounterState) {}
                override fun onDispatchAfterClose(action: CounterAction) {
                    dispatchedAfterClose.add(action)
                }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                logger = testLogger,
                scope = storeScope,
            )

            store.close()

            // Dispatch after close
            store.dispatch(CounterAction.Increment)
            store.dispatch(CounterAction.Add(5))

            // Wait for coroutines to complete
            advanceUntilIdle()

            assertEquals(2, dispatchedAfterClose.size)
            assertEquals(CounterAction.Increment, dispatchedAfterClose[0])
            assertEquals(CounterAction.Add(5), dispatchedAfterClose[1])
        }

    // ==================== Duplicate State Emission Tests ====================

    @Test
    fun `store does not emit duplicate states`() =
        runTest {
            var emissionCount = 0

            val store = createStore(
                initialState = CounterState(count = 5),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(5, awaitItem().count)
                emissionCount++

                // Dispatch action that sets the same value
                store.dispatch(CounterAction.SetValue(5))

                // No new emission should occur because state is the same
                expectNoEvents()

                // Dispatch action that actually changes the state
                store.dispatch(CounterAction.SetValue(10))
                assertEquals(10, awaitItem().count)
                emissionCount++

                // Dispatch same value again
                store.dispatch(CounterAction.SetValue(10))
                expectNoEvents()

                // Change state and verify emission
                store.dispatch(CounterAction.Increment)
                assertEquals(11, awaitItem().count)
                emissionCount++

                assertEquals(3, emissionCount)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `store does not emit when reducer returns same state reference`() =
        runTest {
            // FetchData action returns the same state (state unchanged)
            val store = createStore(
                initialState = CounterState(count = 0),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // FetchData returns state unchanged
                store.dispatch(CounterAction.FetchData("test"))
                expectNoEvents()

                // StreamConnected also returns state unchanged
                store.dispatch(CounterAction.StreamConnected(emptyFlow()))
                expectNoEvents()

                // Actual state change should emit
                store.dispatch(CounterAction.Increment)
                assertEquals(1, awaitItem().count)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `consecutive identical state updates are deduplicated`() =
        runTest {
            val emissions = mutableListOf<Int>()

            val store = createStore(
                initialState = CounterState(count = 0),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                emissions.add(awaitItem().count)

                // Multiple actions that result in same state
                store.dispatch(CounterAction.SetValue(10))
                emissions.add(awaitItem().count)

                store.dispatch(CounterAction.SetValue(10))
                store.dispatch(CounterAction.SetValue(10))
                store.dispatch(CounterAction.SetValue(10))

                // Give time for potential emissions
                expectNoEvents()

                store.dispatch(CounterAction.SetValue(20))
                emissions.add(awaitItem().count)

                // Only initial, first change, and final change should be recorded
                assertEquals(listOf(0, 10, 20), emissions)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ==================== Concurrent Dispatch + Close Race Tests ====================
    // NOTE: Using runBlocking with Dispatchers.Default to ensure real multi-threading

    @Test
    fun `concurrent close from multiple coroutines does not throw`() =
        runBlocking {
            val storeScope = CoroutineScope(Dispatchers.Default + Job())
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )
            delay(50) // Let store initialize

            // Launch multiple coroutines that all try to close simultaneously
            val jobs = (1..10).map {
                launch(Dispatchers.Default) { store.close() }
            }
            jobs.joinAll()

            assertTrue(store.isClosed)
        }

    @Test
    fun `dispatch during close does not throw`() =
        runBlocking {
            val storeScope = CoroutineScope(Dispatchers.Default + Job())
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )
            delay(50) // Let store initialize

            // Dispatch and close concurrently — neither should throw
            val dispatchJob = launch(Dispatchers.Default) {
                repeat(100) { store.dispatch(CounterAction.Increment) }
            }
            val closeJob = launch(Dispatchers.Default) {
                store.close()
            }

            dispatchJob.join()
            closeJob.join()
            assertTrue(store.isClosed)
        }

    @Test
    fun `closeGracefully during concurrent dispatch does not throw`() =
        runBlocking {
            val storeScope = CoroutineScope(Dispatchers.Default + Job())
            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )
            delay(50) // Let store initialize

            val dispatchJob = launch(Dispatchers.Default) {
                repeat(50) { store.dispatch(CounterAction.Increment) }
            }
            val closeJob = launch(Dispatchers.Default) {
                store.closeGracefully()
            }

            dispatchJob.join()
            closeJob.join()
            assertTrue(store.isClosed)
        }
}
