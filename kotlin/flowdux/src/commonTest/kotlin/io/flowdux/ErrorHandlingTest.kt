package io.flowdux

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ErrorHandlingTest {

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
                        state.copy(count = -1)
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
                    return flowOf(CounterAction.SetValue(-1))
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
}
