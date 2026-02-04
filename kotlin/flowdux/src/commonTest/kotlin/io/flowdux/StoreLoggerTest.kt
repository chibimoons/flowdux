package io.flowdux

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoreLoggerTest {

    private class TestLogger : StoreLogger<CounterState, CounterAction> {
        val dispatched = mutableListOf<CounterAction>()
        val middlewareProcessing = mutableListOf<Pair<String, CounterAction>>()
        val middlewaresCompleted = mutableListOf<CounterAction>()
        val flowHolderEmitted = mutableListOf<CounterAction>()
        val errors = mutableListOf<Throwable>()
        val errorsHandled = mutableListOf<CounterAction>()
        val stateReduced = mutableListOf<Triple<CounterAction, CounterState, CounterState>>()
        val dispatchedAfterClose = mutableListOf<CounterAction>()

        override fun onActionDispatched(action: CounterAction) {
            dispatched.add(action)
        }

        override fun onMiddlewareProcessing(middlewareName: String, action: CounterAction) {
            middlewareProcessing.add(middlewareName to action)
        }

        override fun onMiddlewaresCompleted(action: CounterAction) {
            middlewaresCompleted.add(action)
        }

        override fun onFlowHolderActionEmitted(action: CounterAction) {
            flowHolderEmitted.add(action)
        }

        override fun onErrorOccurred(throwable: Throwable) {
            errors.add(throwable)
        }

        override fun onErrorHandled(action: CounterAction) {
            errorsHandled.add(action)
        }

        override fun onStateReduced(action: CounterAction, previousState: CounterState, newState: CounterState) {
            stateReduced.add(Triple(action, previousState, newState))
        }

        override fun onDispatchAfterClose(action: CounterAction) {
            dispatchedAfterClose.add(action)
        }
    }

    @Test
    fun `onActionDispatched is called for each dispatch`() = runTest {
        val logger = TestLogger()
        val store = createStore(
            initialState = CounterState(),
            reducer = counterReducer,
            errorProcessor = testErrorProcessor,
            logger = logger,
            scope = backgroundScope,
        )

        store.state.test {
            awaitItem()

            store.dispatch(CounterAction.Increment)
            awaitItem()

            store.dispatch(CounterAction.Add(5))
            awaitItem()

            assertEquals(2, logger.dispatched.size)
            assertEquals(CounterAction.Increment, logger.dispatched[0])
            assertEquals(CounterAction.Add(5), logger.dispatched[1])

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onStateReduced is called with correct previous and new state`() = runTest {
        val logger = TestLogger()
        val store = createStore(
            initialState = CounterState(count = 0),
            reducer = counterReducer,
            errorProcessor = testErrorProcessor,
            logger = logger,
            scope = backgroundScope,
        )

        store.state.test {
            awaitItem()

            store.dispatch(CounterAction.Increment)
            awaitItem()

            store.dispatch(CounterAction.Add(10))
            awaitItem()

            assertEquals(2, logger.stateReduced.size)

            val (action1, prev1, new1) = logger.stateReduced[0]
            assertEquals(CounterAction.Increment, action1)
            assertEquals(0, prev1.count)
            assertEquals(1, new1.count)

            val (action2, prev2, new2) = logger.stateReduced[1]
            assertEquals(CounterAction.Add(10), action2)
            assertEquals(1, prev2.count)
            assertEquals(11, new2.count)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onMiddlewareProcessing is called for each middleware`() = runTest {
        val logger = TestLogger()
        val passthrough = object : Middleware<CounterState, CounterAction> {
            override val name = "PassthroughMiddleware"
            override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()
            override fun process(getState: () -> CounterState, action: CounterAction): Flow<CounterAction> =
                flowOf(action)
        }

        val store = createStore(
            initialState = CounterState(),
            reducer = counterReducer,
            middlewares = listOf(passthrough),
            errorProcessor = testErrorProcessor,
            logger = logger,
            scope = backgroundScope,
        )

        store.state.test {
            awaitItem()

            store.dispatch(CounterAction.Increment)
            awaitItem()

            // passthrough + FlowHolderMiddleware = 2 processing calls
            assertEquals(2, logger.middlewareProcessing.size)
            assertEquals("PassthroughMiddleware", logger.middlewareProcessing[0].first)
            assertEquals("FlowHolderMiddleware", logger.middlewareProcessing[1].first)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onMiddlewaresCompleted is called after middleware chain`() = runTest {
        val logger = TestLogger()
        val store = createStore(
            initialState = CounterState(),
            reducer = counterReducer,
            errorProcessor = testErrorProcessor,
            logger = logger,
            scope = backgroundScope,
        )

        store.state.test {
            awaitItem()

            store.dispatch(CounterAction.Increment)
            awaitItem()

            assertEquals(1, logger.middlewaresCompleted.size)
            assertEquals(CounterAction.Increment, logger.middlewaresCompleted[0])

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onFlowHolderActionEmitted is called for FlowHolderAction inner actions`() = runTest {
        val logger = TestLogger()
        val store = createStore(
            initialState = CounterState(),
            reducer = counterReducer,
            errorProcessor = testErrorProcessor,
            logger = logger,
            scope = backgroundScope,
        )

        store.state.test {
            awaitItem()

            store.dispatch(CounterAction.StreamConnected(flowOf(1, 2)))
            awaitItem() // count=1
            awaitItem() // count=3

            // Two inner actions emitted from the FlowHolderAction
            assertEquals(2, logger.flowHolderEmitted.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onErrorOccurred and onErrorHandled are called on middleware error`() = runTest {
        val logger = TestLogger()

        val errorProcessor = object : ErrorProcessor<CounterAction> {
            override fun process(throwable: Throwable): Flow<CounterAction> =
                flowOf(CounterAction.SetValue(-1))
        }

        val throwingMiddleware = object : Middleware<CounterState, CounterAction> {
            override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()
            override fun process(getState: () -> CounterState, action: CounterAction): Flow<CounterAction> =
                flow {
                    if (action is CounterAction.FetchData) {
                        throw RuntimeException("test error")
                    }
                    emit(action)
                }
        }

        val store = createStore(
            initialState = CounterState(),
            reducer = counterReducer,
            middlewares = listOf(throwingMiddleware),
            errorProcessor = errorProcessor,
            logger = logger,
            scope = backgroundScope,
        )

        store.state.test {
            awaitItem()

            store.dispatch(CounterAction.FetchData("crash"))
            awaitItem() // SetValue(-1)

            assertEquals(1, logger.errors.size)
            assertEquals("test error", logger.errors[0].message)

            assertEquals(1, logger.errorsHandled.size)
            assertEquals(CounterAction.SetValue(-1), logger.errorsHandled[0])

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onDispatchAfterClose is called when dispatching after close`() = runTest {
        val logger = TestLogger()
        val store = createStore(
            initialState = CounterState(),
            reducer = counterReducer,
            errorProcessor = testErrorProcessor,
            logger = logger,
            scope = backgroundScope,
        )

        store.close()

        store.dispatch(CounterAction.Increment)
        store.dispatch(CounterAction.Add(5))

        advanceUntilIdle()

        assertEquals(2, logger.dispatchedAfterClose.size)
        assertEquals(CounterAction.Increment, logger.dispatchedAfterClose[0])
        assertEquals(CounterAction.Add(5), logger.dispatchedAfterClose[1])
    }

    @Test
    fun `NoOpStoreLogger subclass with overrides receives callbacks`() = runTest {
        val reducedActions = mutableListOf<CounterAction>()

        val subclassLogger = object : NoOpStoreLogger<CounterState, CounterAction>() {
            override fun onStateReduced(action: CounterAction, previousState: CounterState, newState: CounterState) {
                reducedActions.add(action)
            }
        }

        val store = createStore(
            initialState = CounterState(),
            reducer = counterReducer,
            errorProcessor = testErrorProcessor,
            logger = subclassLogger,
            scope = backgroundScope,
        )

        store.state.test {
            awaitItem()

            store.dispatch(CounterAction.Increment)
            awaitItem()

            store.dispatch(CounterAction.Add(5))
            awaitItem()

            assertEquals(2, reducedActions.size)
            assertEquals(CounterAction.Increment, reducedActions[0])
            assertEquals(CounterAction.Add(5), reducedActions[1])

            cancelAndIgnoreRemainingEvents()
        }
    }
}
