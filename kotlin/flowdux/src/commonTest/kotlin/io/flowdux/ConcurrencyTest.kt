package io.flowdux

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ConcurrencyTest {
    @Test
    fun `concurrent increments should not lose updates due to race condition`() = runBlocking {
        // This test verifies that concurrent actions don't cause lost updates
        // due to reading stale state before acquiring mutex lock.
        //
        // Race condition scenario (if broken):
        // 1. Action A reads state.value = 0 (before mutex)
        // 2. Action B reads state.value = 0 (before mutex, A not yet completed)
        // 3. Action A acquires mutex, reduces 0 -> 1, releases mutex
        // 4. Action B acquires mutex, reduces 0 -> 1 (using stale state!), releases mutex
        // Result: count = 1, but expected count = 2
        //
        // NOTE: Using runBlocking with Dispatchers.Default to ensure real multi-threading
        // NOTE: Reducer includes delay to force context switch during reduce phase

        val concurrentActionCount = 100
        val storeScope = CoroutineScope(Dispatchers.Default + Job())

        // Middleware that adds delay to ensure concurrent execution at reduce phase
        val delayMiddleware =
            object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(getState: () -> CounterState, action: CounterAction): Flow<CounterAction> = flow {
                    if (action is CounterAction.Increment) {
                        delay(10) // Small delay to increase chance of concurrent reduce
                    }
                    emit(action)
                }
            }

        // Reducer that includes delay to simulate slow reduce operation
        // This delay happens AFTER state.value is read but BEFORE mutex is acquired,
        // creating a window for race condition
        val slowReducer =
            Reducer<CounterState, CounterAction> { state, action ->
                when (action) {
                    is CounterAction.Increment -> {
                        // Delay to force context switch - this is where race condition occurs
                        // because state.value was already read before this point
                        // Small busy wait to slow down without yielding the coroutine
                        val start =
                            kotlin.time.TimeSource.Monotonic
                                .markNow()
                        @Suppress("ControlFlowWithEmptyBody")
                        while (start.elapsedNow().inWholeMilliseconds < 1) { }
                        state.copy(count = state.count + 1)
                    }
                    else -> counterReducer.reduce(state, action)
                }
            }

        val store =
            createStore(
                initialState = CounterState(count = 0),
                reducer = slowReducer,
                middlewares = listOf(delayMiddleware),
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )

        try {
            store.state.test {
                assertEquals(0, awaitItem().count)

                // Dispatch many increments concurrently
                repeat(concurrentActionCount) {
                    store.dispatch(CounterAction.Increment)
                }

                // Collect all state updates
                var lastCount = 0
                repeat(concurrentActionCount) {
                    lastCount = awaitItem().count
                }

                // If synchronization is correct, final count should equal the number of actions
                assertEquals(
                    concurrentActionCount,
                    lastCount,
                    "Expected $concurrentActionCount but got $lastCount. " +
                        "This indicates a race condition where some increments were lost.",
                )

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `concurrent add operations should accumulate correctly`() = runBlocking {
        // Similar to increment test but with Add operations
        // Each Add(1) should increment by 1, so 50 Add(1) actions should result in 50
        //
        // NOTE: Using runBlocking with Dispatchers.Default to ensure real multi-threading

        val concurrentActionCount = 50
        val storeScope = CoroutineScope(Dispatchers.Default + Job())

        val delayMiddleware =
            object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(getState: () -> CounterState, action: CounterAction): Flow<CounterAction> = flow {
                    if (action is CounterAction.Add) {
                        delay(5)
                    }
                    emit(action)
                }
            }

        val store =
            createStore(
                initialState = CounterState(count = 0),
                reducer = counterReducer,
                middlewares = listOf(delayMiddleware),
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )

        try {
            store.state.test {
                assertEquals(0, awaitItem().count)

                repeat(concurrentActionCount) {
                    store.dispatch(CounterAction.Add(1))
                }

                var lastCount = 0
                repeat(concurrentActionCount) {
                    lastCount = awaitItem().count
                }

                assertEquals(
                    concurrentActionCount,
                    lastCount,
                    "Expected $concurrentActionCount but got $lastCount. Race condition detected.",
                )

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `mixed fast and slow actions should maintain state consistency`() = runBlocking {
        // Test scenario: slow IO action followed by fast actions
        // All actions should be properly synchronized
        //
        // NOTE: Using runBlocking with Dispatchers.Default to ensure real multi-threading

        val storeScope = CoroutineScope(Dispatchers.Default + Job())

        val slowMiddleware =
            object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(getState: () -> CounterState, action: CounterAction): Flow<CounterAction> = flow {
                    when (action) {
                        is CounterAction.FetchData -> {
                            delay(100) // Slow IO
                            emit(CounterAction.Add(10))
                        }
                        else -> emit(action)
                    }
                }
            }

        val store =
            createStore(
                initialState = CounterState(count = 0),
                reducer = counterReducer,
                middlewares = listOf(slowMiddleware),
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )

        try {
            store.state.test {
                assertEquals(0, awaitItem().count)

                // Dispatch slow action
                store.dispatch(CounterAction.FetchData("slow"))

                // Dispatch fast actions while slow is processing
                store.dispatch(CounterAction.Increment) // +1
                store.dispatch(CounterAction.Increment) // +1
                store.dispatch(CounterAction.Increment) // +1

                // Fast actions complete first
                assertEquals(1, awaitItem().count)
                assertEquals(2, awaitItem().count)
                assertEquals(3, awaitItem().count)

                // Slow action completes and should add 10 to current state (3)
                assertEquals(13, awaitItem().count)

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `high concurrency stress test for state synchronization`() = runBlocking {
        // Stress test with high concurrency to detect any synchronization issues
        //
        // NOTE: Using runBlocking with Dispatchers.Default to ensure real multi-threading

        val actionCount = 200
        val storeScope = CoroutineScope(Dispatchers.Default + Job())

        val store =
            createStore(
                initialState = CounterState(count = 0),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )

        try {
            store.state.test {
                assertEquals(0, awaitItem().count)

                // Dispatch many actions as fast as possible
                repeat(actionCount) {
                    store.dispatch(CounterAction.Increment)
                }

                // Wait for all to complete and verify final state
                var lastCount = 0
                repeat(actionCount) {
                    lastCount = awaitItem().count
                }

                assertEquals(
                    actionCount,
                    lastCount,
                    "State synchronization failed under high concurrency",
                )

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            storeScope.cancel()
        }
    }
}
