package io.flowdux

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IOTest {

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
}
