package io.flowdux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests to verify that `stateIn` updates `state.value` synchronously before the next `map` call.
 *
 * These tests empirically validate the conclusion from `store-synchronization-investigation.md`
 * that the Store pipeline has no synchronization issues despite using `flatMapMerge`.
 *
 * If `stateIn` updated asynchronously, we would observe "lost updates" where multiple reductions
 * read the same stale state value.
 *
 * NOTE: These tests use `runBlocking` with `Dispatchers.Default` to ensure real multi-threading.
 * Using `runTest` would not properly test concurrent execution since it uses a test dispatcher.
 *
 * @see <a href="https://github.com/chibimoons/flowdux/issues/80">Issue #80</a>
 */
class StateInTimingTest {

    /**
     * Test 1: Lost Update Detection (Empirical Test)
     *
     * Dispatches N increments with random delays to maximize concurrency via flatMapMerge.
     * If `stateIn` updates synchronously, final count should equal N.
     * If there are stale reads, final count will be less than N.
     */
    @Test
    fun `state value is always up-to-date before next reduce - no lost updates`() = runBlocking {
        val actionCount = 100
        val storeScope = CoroutineScope(Dispatchers.Default + Job())

        // Middleware with random delay to force concurrent execution through flatMapMerge
        val randomDelayMiddleware = object : Middleware<CounterState, CounterAction> {
            override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

            override fun process(
                getState: () -> CounterState,
                action: CounterAction,
            ): Flow<CounterAction> = flow {
                if (action is CounterAction.Increment) {
                    delay(Random.nextLong(1, 20)) // Random delay to maximize concurrency
                }
                emit(action)
            }
        }

        val store = createStore(
            initialState = CounterState(count = 0),
            reducer = counterReducer,
            middlewares = listOf(randomDelayMiddleware),
            errorProcessor = testErrorProcessor,
            scope = storeScope,
        )

        try {
            repeat(actionCount) { store.dispatch(CounterAction.Increment) }
            delay(3000) // Wait for all actions to complete

            // If state.value is always up-to-date before each reduce → count == actionCount
            // If stale reads occurred → count < actionCount (lost updates)
            assertEquals(
                actionCount,
                store.state.value.count,
                "Expected $actionCount but got ${store.state.value.count}. " +
                    "This indicates lost updates due to stale state reads."
            )
        } finally {
            store.close()
            storeScope.cancel()
        }
    }

    /**
     * Test 2: Each Reduction Reads Previous Reduction's Result (Precision Test)
     *
     * Uses StoreLogger to capture (oldState, newState) pairs for each reduction.
     * Verifies that each reduction reads the exact result of the previous reduction,
     * confirming sequential state progression: (0,1), (1,2), (2,3), ...
     */
    @Test
    fun `each reduction reads the result of the previous reduction`() = runBlocking {
        val actionCount = 50
        val storeScope = CoroutineScope(Dispatchers.Default + Job())
        val readLog = mutableListOf<Pair<Int, Int>>() // (oldState.count, newState.count)

        // Logger that captures state before and after each reduction
        val capturingLogger = object : NoOpStoreLogger<CounterState, CounterAction>() {
            override fun onStateReduced(
                action: CounterAction,
                previousState: CounterState,
                newState: CounterState,
            ) {
                // No synchronization needed - map operator in Store's Flow pipeline runs sequentially
                readLog.add(previousState.count to newState.count)
            }
        }

        // Middleware with random delay
        val randomDelayMiddleware = object : Middleware<CounterState, CounterAction> {
            override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

            override fun process(
                getState: () -> CounterState,
                action: CounterAction,
            ): Flow<CounterAction> = flow {
                if (action is CounterAction.Increment) {
                    delay(Random.nextLong(1, 10))
                }
                emit(action)
            }
        }

        val store = createStore(
            initialState = CounterState(count = 0),
            reducer = counterReducer,
            middlewares = listOf(randomDelayMiddleware),
            errorProcessor = testErrorProcessor,
            logger = capturingLogger,
            scope = storeScope,
        )

        try {
            repeat(actionCount) { store.dispatch(CounterAction.Increment) }
            delay(2000) // Wait for all actions to complete

            assertEquals(
                actionCount,
                readLog.size,
                "Expected $actionCount reductions but got ${readLog.size}"
            )

            // Verify sequential progression in logging order: (0,1), (1,2), (2,3), ...
            // If stateIn updates synchronously, log should already be sequential without sorting
            readLog.forEachIndexed { index, (old, new) ->
                assertEquals(
                    index,
                    old,
                    "Reduction #$index read stale state: expected $index, got $old"
                )
                assertEquals(
                    index + 1,
                    new,
                    "Reduction #$index produced wrong state: expected ${index + 1}, got $new"
                )
            }
        } finally {
            store.close()
            storeScope.cancel()
        }
    }

    /**
     * Test 3: Stress Test (Reliability Test)
     *
     * Repeats the lost update detection test multiple times to catch intermittent failures.
     * A single run may pass by chance even if there's a race condition.
     */
    @Test
    fun `stress test - no lost updates under heavy concurrency`() = runBlocking {
        val trials = 10
        val actionsPerTrial = 50

        repeat(trials) { trial ->
            val storeScope = CoroutineScope(Dispatchers.Default + Job())

            val randomDelayMiddleware = object : Middleware<CounterState, CounterAction> {
                override val processors: ActionProcessorMap<CounterState, CounterAction> = emptyMap()

                override fun process(
                    getState: () -> CounterState,
                    action: CounterAction,
                ): Flow<CounterAction> = flow {
                    if (action is CounterAction.Increment) {
                        delay(Random.nextLong(1, 15))
                    }
                    emit(action)
                }
            }

            val store = createStore(
                initialState = CounterState(count = 0),
                reducer = counterReducer,
                middlewares = listOf(randomDelayMiddleware),
                errorProcessor = testErrorProcessor,
                scope = storeScope,
            )

            try {
                repeat(actionsPerTrial) { store.dispatch(CounterAction.Increment) }
                delay(2000)

                assertEquals(
                    actionsPerTrial,
                    store.state.value.count,
                    "Trial #$trial: Lost update detected. Expected $actionsPerTrial, got ${store.state.value.count}"
                )
            } finally {
                store.close()
                storeScope.cancel()
            }
        }
    }
}
