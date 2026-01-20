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
    fun `chaining two resilience strategies throws exception`() {
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            retry(3) then retryWithBackoff(3, 100.milliseconds)
        }
        assertTrue(exception.message!!.contains("RESILIENCE"))
    }
}
