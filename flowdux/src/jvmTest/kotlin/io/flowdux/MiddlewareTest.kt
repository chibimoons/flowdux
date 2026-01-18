package io.flowdux

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MiddlewareTest {

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
    fun `middleware passes through unregistered actions to reducer`() =
        runTest {
            val middleware = object : Middleware<CounterState, CounterAction> {
                override val processors = buildProcessors {
                    on<CounterAction.FetchData> { state, action ->
                        emit(CounterAction.Add(100))
                    }
                }
            }

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                middlewares = listOf(middleware),
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                // Unregistered action should pass through to reducer
                store.dispatch(CounterAction.Increment)
                assertEquals(1, awaitItem().count)

                store.dispatch(CounterAction.Add(5))
                assertEquals(6, awaitItem().count)

                // Registered action should be handled by middleware
                store.dispatch(CounterAction.FetchData("test"))
                assertEquals(106, awaitItem().count)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `multiple middlewares pass through unregistered actions`() =
        runTest {
            val firstMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors = buildProcessors {
                    on<CounterAction.FetchData> { _, action ->
                        emit(CounterAction.Add(10))
                    }
                }
            }

            val secondMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors = buildProcessors {
                    on<CounterAction.Reset> { _, _ ->
                        emit(CounterAction.SetValue(0))
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

                // Unregistered in both middlewares - should pass through
                store.dispatch(CounterAction.Increment)
                assertEquals(1, awaitItem().count)

                store.dispatch(CounterAction.Increment)
                assertEquals(2, awaitItem().count)

                // Registered in first middleware
                store.dispatch(CounterAction.FetchData("test"))
                assertEquals(12, awaitItem().count)

                // Registered in second middleware
                store.dispatch(CounterAction.Reset)
                assertEquals(0, awaitItem().count)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `duplicate processor registration throws exception`() {
        org.junit.jupiter.api.assertThrows<Middleware.DuplicateProcessorException> {
            object : Middleware<CounterState, CounterAction> {
                override val processors = buildProcessors {
                    on<CounterAction.Increment> { _, _ -> emit(CounterAction.Increment) }
                    on<CounterAction.Increment> { _, _ -> emit(CounterAction.Increment) } // Duplicate!
                }
            }
        }
    }

    @Test
    fun `duplicate processor in group throws exception`() {
        org.junit.jupiter.api.assertThrows<Middleware.DuplicateProcessorException> {
            object : Middleware<CounterState, CounterAction> {
                override val processors = buildProcessors {
                    on<CounterAction.Increment> { _, _ -> emit(CounterAction.Increment) }
                    group(takeLatest()) {
                        on<CounterAction.Increment> { _, _ -> emit(CounterAction.Increment) } // Duplicate!
                    }
                }
            }
        }
    }

    @Test
    fun `duplicate processor across groups throws exception`() {
        org.junit.jupiter.api.assertThrows<Middleware.DuplicateProcessorException> {
            object : Middleware<CounterState, CounterAction> {
                override val processors = buildProcessors {
                    group(takeLatest()) {
                        on<CounterAction.Increment> { _, _ -> emit(CounterAction.Increment) }
                    }
                    group(takeLatest()) {
                        on<CounterAction.Increment> { _, _ -> emit(CounterAction.Increment) } // Duplicate!
                    }
                }
            }
        }
    }
}
