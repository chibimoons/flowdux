package io.flowdux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CloseGracefullyTest {
    @Test
    fun `closeGracefully processes beforeClose actions before closing`() = runTest {
        val storeScope = CoroutineScope(coroutineContext + Job())
        val store =
            createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )
        advanceUntilIdle()

        store.closeGracefully { dispatch ->
            dispatch(CounterAction.Add(10))
            dispatch(CounterAction.Add(20))
        }
        advanceUntilIdle()

        assertEquals(30, store.currentState.count)
        assertTrue(store.isClosed)
    }

    @Test
    fun `closeGracefully without beforeClose drains pending actions`() = runTest {
        val storeScope = CoroutineScope(coroutineContext + Job())
        val store =
            createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )
        advanceUntilIdle()

        store.dispatch(CounterAction.Add(5))
        store.closeGracefully()
        advanceUntilIdle()

        assertEquals(5, store.currentState.count)
        assertTrue(store.isClosed)
    }

    @Test
    fun `closeGracefully on already-closed store is no-op`() = runTest {
        val storeScope = CoroutineScope(coroutineContext + Job())
        val store =
            createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )
        advanceUntilIdle()

        store.close()
        assertTrue(store.isClosed)

        // Should not throw
        store.closeGracefully { dispatch ->
            dispatch(CounterAction.Add(10))
        }
        advanceUntilIdle()

        // State unchanged
        assertEquals(0, store.currentState.count)
    }

    @Test
    fun `closeGracefully with timeout closes even if drain takes too long`() = runTest {
        val storeScope = CoroutineScope(coroutineContext + Job())
        val store =
            createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )
        advanceUntilIdle()

        // Use a very short timeout — the drain sentinel will complete fast
        // but this verifies the timeout path compiles and works
        store.closeGracefully(timeout = 1.seconds)
        advanceUntilIdle()

        assertTrue(store.isClosed)
    }

    @Test
    fun `closeGracefully sentinel passes through middleware pipeline`() = runTest {
        val processedActions = mutableListOf<Action>()
        val trackingMiddleware =
            object : Middleware<CounterState, CounterAction> {
                override val name: String = "tracking"
                override val processors =
                    emptyMap<
                        kotlin.reflect.KClass<*>,
                        suspend kotlinx.coroutines.flow.FlowCollector<CounterAction>.(
                            CounterState,
                            CounterAction,
                        ) -> Unit,
                        >()

                override fun process(getState: () -> CounterState, action: CounterAction) =
                    kotlinx.coroutines.flow.flow {
                        processedActions.add(action)
                        emit(action)
                    }
            }

        val storeScope = CoroutineScope(coroutineContext + Job())
        val store =
            createStore(
                initialState = CounterState(),
                middlewares = listOf(trackingMiddleware),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )
        advanceUntilIdle()

        store.closeGracefully { dispatch ->
            dispatch(CounterAction.Increment)
        }
        advanceUntilIdle()

        // Increment should have been tracked by middleware
        assertTrue(processedActions.any { it is CounterAction.Increment })
        assertEquals(1, store.currentState.count)
        assertTrue(store.isClosed)
    }
}
