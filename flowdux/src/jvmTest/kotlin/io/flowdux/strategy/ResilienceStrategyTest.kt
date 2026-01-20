package io.flowdux.strategy

import app.cash.turbine.test
import io.flowdux.ErrorProcessor
import io.flowdux.Middleware
import io.flowdux.StrategyCategory
import io.flowdux.createStore
import io.flowdux.retry
import io.flowdux.retryWithBackoff
import io.flowdux.strategy.ExecutionStrategyTestBase.TestAction
import io.flowdux.strategy.ExecutionStrategyTestBase.TestState
import io.flowdux.strategy.ExecutionStrategyTestBase.testErrorProcessor
import io.flowdux.strategy.ExecutionStrategyTestBase.testReducer
import io.flowdux.then
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ResilienceStrategyTest {

    @Test
    fun `retry has correct category`() {
        assertEquals(StrategyCategory.RESILIENCE, retry(3).category)
        assertEquals(StrategyCategory.RESILIENCE, retryWithBackoff(3, 100.milliseconds).category)
    }

    @Test
    fun `retry succeeds on first attempt`() = runTest {
        val attemptCount = mutableListOf<Int>()
        var attempt = 0

        val middleware = object : Middleware<TestState, TestAction> {
            override val processors = buildProcessors {
                on<TestAction.Fetch>(retry(3)) { _, action ->
                    attemptCount.add(++attempt)
                    emit(TestAction.FetchSuccess(action.id, "result-${action.id}"))
                }
            }
        }

        val store = createStore(
            initialState = TestState(),
            reducer = testReducer,
            middlewares = listOf(middleware),
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        store.state.test {
            assertEquals(emptyList<String>(), awaitItem().values)

            store.dispatch(TestAction.Fetch("1"))
            val result = awaitItem()

            assertEquals(listOf("result-1"), result.values)
            assertEquals(listOf(1), attemptCount) // Only one attempt

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retry retries on failure and succeeds`() = runTest {
        val attemptCount = mutableListOf<Int>()
        var attempt = 0

        val middleware = object : Middleware<TestState, TestAction> {
            override val processors = buildProcessors {
                on<TestAction.Fetch>(retry(3)) { _, action ->
                    attemptCount.add(++attempt)
                    if (attempt < 3) {
                        throw RuntimeException("Simulated failure $attempt")
                    }
                    emit(TestAction.FetchSuccess(action.id, "result-${action.id}"))
                }
            }
        }

        val store = createStore(
            initialState = TestState(),
            reducer = testReducer,
            middlewares = listOf(middleware),
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        store.state.test {
            assertEquals(emptyList<String>(), awaitItem().values)

            store.dispatch(TestAction.Fetch("1"))
            val result = awaitItem()

            assertEquals(listOf("result-1"), result.values)
            assertEquals(listOf(1, 2, 3), attemptCount) // Three attempts

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retry throws after max attempts exhausted`() = runTest {
        val attemptCount = mutableListOf<Int>()
        var attempt = 0
        var caughtException: Throwable? = null

        val errorProcessor = object : ErrorProcessor<TestAction> {
            override fun process(throwable: Throwable): Flow<TestAction> {
                caughtException = throwable
                return emptyFlow()
            }
        }

        val middleware = object : Middleware<TestState, TestAction> {
            override val processors = buildProcessors {
                on<TestAction.Fetch>(retry(3)) { _, _ ->
                    attemptCount.add(++attempt)
                    throw RuntimeException("Always fails")
                }
            }
        }

        val store = createStore(
            initialState = TestState(),
            reducer = testReducer,
            middlewares = listOf(middleware),
            errorProcessor = errorProcessor,
            scope = backgroundScope,
        )

        store.state.test {
            assertEquals(emptyList<String>(), awaitItem().values)

            store.dispatch(TestAction.Fetch("1"))
            advanceTimeBy(100)

            assertEquals(listOf(1, 2, 3), attemptCount) // All three attempts made
            assertTrue(caughtException is RuntimeException)
            assertEquals("Always fails", caughtException?.message)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retry respects retryIf predicate`() = runTest {
        val attemptCount = mutableListOf<Int>()
        var attempt = 0
        var caughtException: Throwable? = null

        val errorProcessor = object : ErrorProcessor<TestAction> {
            override fun process(throwable: Throwable): Flow<TestAction> {
                caughtException = throwable
                return emptyFlow()
            }
        }

        // Only retry on IllegalStateException, not on IllegalArgumentException
        val middleware = object : Middleware<TestState, TestAction> {
            override val processors = buildProcessors {
                on<TestAction.Fetch>(retry(3) { it is IllegalStateException }) { _, _ ->
                    attemptCount.add(++attempt)
                    throw IllegalArgumentException("Non-retryable")
                }
            }
        }

        val store = createStore(
            initialState = TestState(),
            reducer = testReducer,
            middlewares = listOf(middleware),
            errorProcessor = errorProcessor,
            scope = backgroundScope,
        )

        store.state.test {
            assertEquals(emptyList<String>(), awaitItem().values)

            store.dispatch(TestAction.Fetch("1"))
            advanceTimeBy(100)

            assertEquals(listOf(1), attemptCount) // Only one attempt - no retry
            assertTrue(caughtException is IllegalArgumentException)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retryWithBackoff delays between retries`() = runTest {
        val attemptTimes = mutableListOf<Long>()
        var attempt = 0
        val startTime = testScheduler.currentTime

        val middleware = object : Middleware<TestState, TestAction> {
            override val processors = buildProcessors {
                on<TestAction.Fetch>(retryWithBackoff(
                    maxAttempts = 4,
                    initialDelay = 100.milliseconds,
                    factor = 2.0
                )) { _, action ->
                    attemptTimes.add(testScheduler.currentTime - startTime)
                    attempt++
                    if (attempt < 4) {
                        throw RuntimeException("Simulated failure $attempt")
                    }
                    emit(TestAction.FetchSuccess(action.id, "result-${action.id}"))
                }
            }
        }

        val store = createStore(
            initialState = TestState(),
            reducer = testReducer,
            middlewares = listOf(middleware),
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        store.state.test {
            assertEquals(emptyList<String>(), awaitItem().values)

            store.dispatch(TestAction.Fetch("1"))

            // Need to advance time for exponential backoff: 100ms, 200ms, 400ms
            advanceTimeBy(100) // First retry delay
            advanceTimeBy(200) // Second retry delay
            advanceTimeBy(400) // Third retry delay

            val result = awaitItem()
            assertEquals(listOf("result-1"), result.values)

            // Verify delays: 0ms, ~100ms, ~300ms (100+200), ~700ms (100+200+400)
            assertEquals(4, attemptTimes.size)
            assertEquals(0L, attemptTimes[0]) // First attempt immediate
            assertTrue(attemptTimes[1] >= 100) // After 100ms delay
            assertTrue(attemptTimes[2] >= 300) // After 100+200ms delays
            assertTrue(attemptTimes[3] >= 700) // After 100+200+400ms delays

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retry does not retry on CancellationException`() = runTest {
        val attemptCount = mutableListOf<Int>()
        var attempt = 0

        val middleware = object : Middleware<TestState, TestAction> {
            override val processors = buildProcessors {
                on<TestAction.Fetch>(retry(3)) { _, action ->
                    attemptCount.add(++attempt)
                    delay(100) // This will be cancelled
                    emit(TestAction.FetchSuccess(action.id, "result-${action.id}"))
                }
            }
        }

        val store = createStore(
            initialState = TestState(),
            reducer = testReducer,
            middlewares = listOf(middleware),
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        store.state.test {
            assertEquals(emptyList<String>(), awaitItem().values)

            // Start action
            store.dispatch(TestAction.Fetch("1"))
            advanceTimeBy(50) // Let it start but not complete

            // Cancel by closing the store
            store.close()
            advanceTimeBy(200)

            // Should only have one attempt - cancellation should not trigger retry
            assertEquals(1, attemptCount.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retryWithBackoff respects maxDelay cap`() = runTest {
        val attemptTimes = mutableListOf<Long>()
        var attempt = 0
        val startTime = testScheduler.currentTime

        val middleware = object : Middleware<TestState, TestAction> {
            override val processors = buildProcessors {
                on<TestAction.Fetch>(retryWithBackoff(
                    maxAttempts = 5,
                    initialDelay = 100.milliseconds,
                    maxDelay = 150.milliseconds, // Cap at 150ms
                    factor = 2.0
                )) { _, action ->
                    attemptTimes.add(testScheduler.currentTime - startTime)
                    attempt++
                    if (attempt < 5) {
                        throw RuntimeException("Simulated failure $attempt")
                    }
                    emit(TestAction.FetchSuccess(action.id, "result-${action.id}"))
                }
            }
        }

        val store = createStore(
            initialState = TestState(),
            reducer = testReducer,
            middlewares = listOf(middleware),
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        store.state.test {
            assertEquals(emptyList<String>(), awaitItem().values)

            store.dispatch(TestAction.Fetch("1"))

            // Delays should be: 100ms, 150ms (capped from 200ms), 150ms (capped from 400ms), 150ms (capped from 800ms)
            // Total: 100 + 150 + 150 + 150 = 550ms
            advanceTimeBy(600)

            val result = awaitItem()
            assertEquals(listOf("result-1"), result.values)

            // Verify delays are capped
            // Attempt 1: 0ms
            // Attempt 2: ~100ms (100ms delay)
            // Attempt 3: ~250ms (100 + 150ms delay, capped from 200)
            // Attempt 4: ~400ms (100 + 150 + 150ms delay, capped from 400)
            // Attempt 5: ~550ms (100 + 150 + 150 + 150ms delay, capped from 800)
            assertEquals(5, attemptTimes.size)

            // Check that delays between attempts don't exceed maxDelay (150ms) + small buffer
            val delay2 = attemptTimes[1] - attemptTimes[0]
            val delay3 = attemptTimes[2] - attemptTimes[1]
            val delay4 = attemptTimes[3] - attemptTimes[2]
            val delay5 = attemptTimes[4] - attemptTimes[3]

            assertEquals(100, delay2) // First delay is 100ms (initialDelay)
            assertTrue(delay3 <= 160, "Delay 3 should be capped at ~150ms but was $delay3")
            assertTrue(delay4 <= 160, "Delay 4 should be capped at ~150ms but was $delay4")
            assertTrue(delay5 <= 160, "Delay 5 should be capped at ~150ms but was $delay5")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `chaining two resilience strategies throws exception`() {
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            retry(3) then retryWithBackoff(3, 100.milliseconds)
        }
        assertTrue(exception.message!!.contains("RESILIENCE"))
    }
}
