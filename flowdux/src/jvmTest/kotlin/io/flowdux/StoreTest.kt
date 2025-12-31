package io.flowdux

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StoreTest {
    data class CounterState(val count: Int = 0) : State

    sealed interface CounterAction : Action {
        object Increment : CounterAction
        object Decrement : CounterAction
        data class Add(val value: Int) : CounterAction
        data class SetValue(val value: Int) : CounterAction
        data class FetchData(val id: String) : CounterAction
        data class FetchDataSuccess(val id: String, val value: Int) : CounterAction
        data class FetchDataError(val id: String, val error: String) : CounterAction
        object Reset : CounterAction

        data class StreamConnected(
            private val valueFlow: Flow<Int>,
        ) : CounterAction, FlowHolderAction {
            override fun toFlowAction(): Flow<Action> = valueFlow.map { Add(it) }
        }

        data class MultiStreamConnected(
            private val flow1: Flow<Int>,
            private val flow2: Flow<Int>,
        ) : CounterAction, FlowHolderAction {
            override fun toFlowAction() = listOf(
                flow1.map { Add(it) },
                flow2.map { Add(it) }
            ).merge()
        }
    }

    private val counterReducer =
        Reducer<CounterState, CounterAction> { state, action ->
            when (action) {
                is CounterAction.Increment -> state.copy(count = state.count + 1)
                is CounterAction.Decrement -> state.copy(count = state.count - 1)
                is CounterAction.Add -> state.copy(count = state.count + action.value)
                is CounterAction.SetValue -> state.copy(count = action.value)
                is CounterAction.FetchData -> state
                is CounterAction.FetchDataSuccess -> state.copy(count = action.value)
                is CounterAction.FetchDataError -> state
                is CounterAction.Reset -> state.copy(count = 0)
                is CounterAction.StreamConnected -> state
                is CounterAction.MultiStreamConnected -> state
            }
        }

    private val testErrorProcessor = object : ErrorProcessor<CounterAction> {
        override fun process(throwable: Throwable): Flow<CounterAction> {
            return emptyFlow()
        }
    }

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

    @Test
    fun `middleware intercepts actions`() =
        runTest {
            val interceptedActions = mutableListOf<CounterAction>()

            val loggingMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()
                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> =
                    flow {
                        interceptedActions.add(action)
                        emit(action)
                    }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                middlewares = listOf(loggingMiddleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.Increment)
                awaitItem()

                store.dispatch(CounterAction.Decrement)
                awaitItem()

                assertEquals(2, interceptedActions.size)
                assertEquals(CounterAction.Increment, interceptedActions[0])
                assertEquals(CounterAction.Decrement, interceptedActions[1])

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `middleware can emit additional actions`() =
        runTest {
            val doublingMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> =
                    flow {
                        emit(action)
                        if (action is CounterAction.Increment) {
                            emit(CounterAction.Increment)
                        }
                    }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                middlewares = listOf(doublingMiddleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.Increment)
                assertEquals(1, awaitItem().count)
                assertEquals(2, awaitItem().count)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `middleware chain executes in order`() =
        runTest {
            val executionOrder = mutableListOf<String>()

            val firstMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> =
                    flow {
                        executionOrder.add("first")
                        emit(action)
                    }
            }

            val secondMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> =
                    flow {
                        executionOrder.add("second")
                        emit(action)
                    }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                middlewares = listOf(firstMiddleware, secondMiddleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.Increment)
                awaitItem()

                assertEquals(listOf("first", "second"), executionOrder)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `middleware can block actions`() =
        runTest {
            val blockingMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> =
                    flow {
                        if (action !is CounterAction.Decrement) {
                            emit(action)
                        }
                    }
            }

            val store = createStore(
                initialState = CounterState(count = 5),
                reducer = counterReducer,
                middlewares = listOf(blockingMiddleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(5, awaitItem().count)

                store.dispatch(CounterAction.Increment)
                assertEquals(6, awaitItem().count)

                store.dispatch(CounterAction.Decrement)
                // Decrement is blocked, no state change expected
                expectNoEvents()

                assertEquals(6, store.currentState.count)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `middleware can access current state`() =
        runTest {
            val stateSnapshots = mutableListOf<Int>()

            val stateTrackingMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> =
                    flow {
                        stateSnapshots.add(getState().count)
                        emit(action)
                    }
            }

            val store = createStore(
                initialState = CounterState(count = 10),
                reducer = counterReducer,
                middlewares = listOf(stateTrackingMiddleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(10, awaitItem().count)

                store.dispatch(CounterAction.Increment)
                awaitItem()

                store.dispatch(CounterAction.Add(5))
                awaitItem()

                assertEquals(listOf(10, 11), stateSnapshots)

                cancelAndIgnoreRemainingEvents()
            }
        }

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

    // ==================== IO Simulation Tests ====================

    @Test
    fun `middleware with IO delay processes action after delay`() =
        runTest {
            val ioMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> = flow {
                    if (action is CounterAction.FetchData) {
                        delay(1000) // Simulate network delay
                        emit(CounterAction.FetchDataSuccess(action.id, 42))
                    } else {
                        emit(action)
                    }
                }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                middlewares = listOf(ioMiddleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.FetchData("test-1"))
                // After delay, FetchDataSuccess should be emitted
                assertEquals(42, awaitItem().count)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `middleware processes multiple IO requests concurrently`() =
        runTest {
            val processedIds = mutableListOf<String>()

            val ioMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> = flow {
                    if (action is CounterAction.FetchData) {
                        delay(100) // Simulate network delay
                        processedIds.add(action.id)
                        emit(CounterAction.Add(10))
                    } else {
                        emit(action)
                    }
                }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                middlewares = listOf(ioMiddleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // Dispatch multiple actions concurrently
                store.dispatch(CounterAction.FetchData("request-1"))
                store.dispatch(CounterAction.FetchData("request-2"))
                store.dispatch(CounterAction.FetchData("request-3"))

                // All three should complete (order may vary due to concurrency)
                assertEquals(10, awaitItem().count)
                assertEquals(20, awaitItem().count)
                assertEquals(30, awaitItem().count)

                assertEquals(3, processedIds.size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `fast action completes before slow IO action`() =
        runTest {
            val executionOrder = mutableListOf<String>()

            val ioMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> = flow {
                    when (action) {
                        is CounterAction.FetchData -> {
                            delay(500) // Slow IO operation
                            executionOrder.add("slow-io")
                            emit(CounterAction.Add(100))
                        }
                        is CounterAction.Increment -> {
                            executionOrder.add("fast-increment")
                            emit(action)
                        }
                        else -> emit(action)
                    }
                }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                middlewares = listOf(ioMiddleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.FetchData("slow"))
                store.dispatch(CounterAction.Increment)

                // Fast action should complete first
                assertEquals(1, awaitItem().count)
                // Then slow IO action completes
                assertEquals(101, awaitItem().count)

                assertEquals(listOf("fast-increment", "slow-io"), executionOrder)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `middleware handles IO error and emits error action`() =
        runTest {
            val ioMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> = flow {
                    if (action is CounterAction.FetchData) {
                        delay(100)
                        if (action.id == "error") {
                            emit(CounterAction.FetchDataError(action.id, "Network error"))
                        } else {
                            emit(CounterAction.FetchDataSuccess(action.id, 50))
                        }
                    } else {
                        emit(action)
                    }
                }
            }

            var errorReceived = false
            val errorTrackingReducer = Reducer<CounterState, CounterAction> { state, action ->
                when (action) {
                    is CounterAction.FetchDataError -> {
                        errorReceived = true
                        state.copy(count = -1) // Change state to indicate error
                    }
                    is CounterAction.FetchDataSuccess -> state.copy(count = action.value)
                    else -> counterReducer.reduce(state, action)
                }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = errorTrackingReducer,
                middlewares = listOf(ioMiddleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.FetchData("error"))
                assertEquals(-1, awaitItem().count)

                assertTrue(errorReceived)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `middleware with IO exception is caught by error processor`() =
        runTest {
            var caughtException: Throwable? = null

            val errorProcessor = object : ErrorProcessor<CounterAction> {
                override fun process(throwable: Throwable): Flow<CounterAction> {
                    caughtException = throwable
                    return flowOf(CounterAction.SetValue(-1)) // Error indicator
                }
            }

            val ioMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> = flow {
                    if (action is CounterAction.FetchData && action.id == "crash") {
                        throw RuntimeException("IO Exception!")
                    }
                    emit(action)
                }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                middlewares = listOf(ioMiddleware),
                errorProcessor = errorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.FetchData("crash"))
                assertEquals(-1, awaitItem().count)

                assertTrue(caughtException is RuntimeException)
                assertEquals("IO Exception!", caughtException?.message)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `middleware emits multiple actions from single IO operation`() =
        runTest {
            val ioMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> = flow {
                    if (action is CounterAction.FetchData) {
                        delay(50)
                        // Emit multiple results from paginated API
                        emit(CounterAction.Add(10))
                        delay(50)
                        emit(CounterAction.Add(20))
                        delay(50)
                        emit(CounterAction.Add(30))
                    } else {
                        emit(action)
                    }
                }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                middlewares = listOf(ioMiddleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.FetchData("paginated"))

                assertEquals(10, awaitItem().count)
                assertEquals(30, awaitItem().count)
                assertEquals(60, awaitItem().count)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `sequential IO operations maintain order with flatMapConcat in middleware chain`() =
        runTest {
            val operationOrder = mutableListOf<String>()

            val firstMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> = flow {
                    if (action is CounterAction.FetchData) {
                        delay(100)
                        operationOrder.add("first-${action.id}")
                    }
                    emit(action)
                }
            }

            val secondMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> = flow {
                    if (action is CounterAction.FetchData) {
                        delay(50)
                        operationOrder.add("second-${action.id}")
                        emit(CounterAction.Add(5))
                    } else {
                        emit(action)
                    }
                }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                middlewares = listOf(firstMiddleware, secondMiddleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.FetchData("A"))
                assertEquals(5, awaitItem().count)

                // For single action, middleware chain is sequential
                assertEquals(listOf("first-A", "second-A"), operationOrder)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `IO operation can read latest state via getState`() =
        runTest {
            val stateAtIOTime = mutableListOf<Int>()

            val ioMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> = flow {
                    if (action is CounterAction.FetchData) {
                        delay(200) // Long IO operation
                        stateAtIOTime.add(getState().count)
                        emit(CounterAction.Add(getState().count * 2))
                    } else {
                        emit(action)
                    }
                }
            }

            val store = createStore(
                initialState = CounterState(count = 10),
                reducer = counterReducer,
                middlewares = listOf(ioMiddleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(10, awaitItem().count)

                store.dispatch(CounterAction.FetchData("read-state"))
                store.dispatch(CounterAction.Increment) // This will complete first

                assertEquals(11, awaitItem().count)
                // IO reads state after Increment completed
                assertEquals(33, awaitItem().count) // 11 + (11 * 2)

                assertEquals(listOf(11), stateAtIOTime)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `long running IO does not block other actions`() =
        runTest {
            val ioMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> = flow {
                    when (action) {
                        is CounterAction.FetchData -> {
                            delay(1000) // Very long operation
                            emit(CounterAction.Add(1000))
                        }
                        else -> emit(action)
                    }
                }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                middlewares = listOf(ioMiddleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.FetchData("long"))
                store.dispatch(CounterAction.Increment)
                store.dispatch(CounterAction.Increment)
                store.dispatch(CounterAction.Increment)

                // Fast actions complete first
                assertEquals(1, awaitItem().count)
                assertEquals(2, awaitItem().count)
                assertEquals(3, awaitItem().count)

                // Long IO completes last
                assertEquals(1003, awaitItem().count)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `error in one IO operation does not affect others`() =
        runTest {
            var errorCount = 0

            val errorProcessor = object : ErrorProcessor<CounterAction> {
                override fun process(throwable: Throwable): Flow<CounterAction> {
                    errorCount++
                    return emptyFlow()
                }
            }

            val ioMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> = flow {
                    if (action is CounterAction.FetchData) {
                        delay(50)
                        if (action.id == "fail") {
                            throw RuntimeException("Failed!")
                        }
                        emit(CounterAction.Add(10))
                    } else {
                        emit(action)
                    }
                }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                middlewares = listOf(ioMiddleware),
                errorProcessor = errorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.FetchData("success-1"))
                store.dispatch(CounterAction.FetchData("fail"))
                store.dispatch(CounterAction.FetchData("success-2"))

                // Two successful operations should complete
                assertEquals(10, awaitItem().count)
                assertEquals(20, awaitItem().count)

                assertEquals(1, errorCount)

                cancelAndIgnoreRemainingEvents()
            }
        }

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

    @Test
    fun `external flow error is caught by error processor`() =
        runTest {
            var errorProcessed = false
            val errorProcessor = object : ErrorProcessor<CounterAction> {
                override fun process(throwable: Throwable): Flow<CounterAction> {
                    errorProcessed = true
                    return flowOf(CounterAction.SetValue(-999))
                }
            }

            val externalChannel = Channel<Int>(Channel.UNLIMITED)
            val errorFlow = externalChannel.receiveAsFlow().map { value ->
                if (value < 0) throw RuntimeException("Negative value error!")
                value
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = errorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.StreamConnected(errorFlow))

                externalChannel.send(10)
                assertEquals(10, awaitItem().count)

                externalChannel.send(-1)
                assertEquals(-999, awaitItem().count)

                assertTrue(errorProcessed)

                cancelAndIgnoreRemainingEvents()
            }

            externalChannel.close()
        }

    @Test
    fun `store continues working after FlowHolderAction error`() =
        runTest {
            val errorProcessor = object : ErrorProcessor<CounterAction> {
                override fun process(throwable: Throwable): Flow<CounterAction> {
                    return flowOf(CounterAction.SetValue(-1))
                }
            }

            val errorFlow = flow<Int> {
                throw RuntimeException("Immediate error!")
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = errorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.StreamConnected(errorFlow))
                assertEquals(-1, awaitItem().count)

                // Store is still working
                store.dispatch(CounterAction.Increment)
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.Add(100))
                assertEquals(100, awaitItem().count)

                cancelAndIgnoreRemainingEvents()
            }
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
}
