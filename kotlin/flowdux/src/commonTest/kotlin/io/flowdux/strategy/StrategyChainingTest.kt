package io.flowdux.strategy

import app.cash.turbine.test
import io.flowdux.ChainedStrategy
import io.flowdux.Middleware
import io.flowdux.StrategyCategory
import io.flowdux.createStore
import io.flowdux.debounce
import io.flowdux.retry
import io.flowdux.retryWithBackoff
import io.flowdux.sequential
import io.flowdux.strategy.ExecutionStrategyTestBase.TestAction
import io.flowdux.strategy.ExecutionStrategyTestBase.TestState
import io.flowdux.strategy.ExecutionStrategyTestBase.testErrorProcessor
import io.flowdux.strategy.ExecutionStrategyTestBase.testReducer
import io.flowdux.takeLatest
import io.flowdux.takeLeading
import io.flowdux.then
import io.flowdux.throttle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class StrategyChainingTest {

    class CategoryValidationTests {

        @Test
        fun `strategies have correct categories`() {
            assertEquals(StrategyCategory.CONCURRENCY, takeLatest().category)
            assertEquals(StrategyCategory.CONCURRENCY, takeLeading().category)
            assertEquals(StrategyCategory.CONCURRENCY, sequential().category)
            assertEquals(StrategyCategory.TIMING, debounce(100.milliseconds).category)
            assertEquals(StrategyCategory.TIMING, throttle(100.milliseconds).category)
            assertEquals(StrategyCategory.RESILIENCE, retry(3).category)
            assertEquals(StrategyCategory.RESILIENCE, retryWithBackoff(3, 100.milliseconds).category)
        }

        @Test
        fun `then chains timing and concurrency strategies`() {
            val chained = debounce(100.milliseconds) then takeLatest()

            assertEquals(StrategyCategory.CHAINED, chained.category)
            assertTrue(chained is ChainedStrategy)
        }

        @Test
        fun `chaining same category throws exception`() {
            // Two CONCURRENCY strategies
            val exception1 = assertFailsWith<IllegalArgumentException> {
                takeLatest() then takeLeading()
            }
            assertTrue(exception1.message!!.contains("CONCURRENCY"))
            assertTrue(exception1.message!!.contains("TakeLatest"))
            assertTrue(exception1.message!!.contains("TakeLeading"))

            // Two TIMING strategies
            val exception2 = assertFailsWith<IllegalArgumentException> {
                debounce(100.milliseconds) then throttle(200.milliseconds)
            }
            assertTrue(exception2.message!!.contains("TIMING"))
        }

        @Test
        fun `three-way chaining works with different categories`() {
            // TIMING then CONCURRENCY then RESILIENCE
            val threeWay = debounce(100.milliseconds) then takeLatest() then retry(3)
            assertEquals(StrategyCategory.CHAINED, threeWay.category)
        }

        @Test
        fun `chained strategy with chained strategy validates all categories`() {
            // (TIMING then CONCURRENCY) then TIMING should fail
            val timingThenConcurrency = debounce(50.milliseconds) then takeLatest()

            val exception = assertFailsWith<IllegalArgumentException> {
                timingThenConcurrency then throttle(100.milliseconds)
            }
            assertTrue(exception.message!!.contains("TIMING"))
        }
    }

    class ChainedExecutionTests {

        @Test
        fun `debounce then takeLatest executes correctly`() = runTest {
            val executionOrder = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    // Use longer processor delay (200ms) so takeLatest can cancel it
                    // when the next action passes through debounce (100ms)
                    on<TestAction.Search>(debounce(100.milliseconds) then takeLatest()) { _, action ->
                        executionOrder.add("start-${action.query}")
                        delay(200)
                        executionOrder.add("end-${action.query}")
                        emit(TestAction.SearchResult(action.query, listOf(action.query)))
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

                // Rapid typing - debounce should delay
                store.dispatch(TestAction.Search("a"))
                advanceTimeBy(50)
                store.dispatch(TestAction.Search("ab"))
                advanceTimeBy(50)
                store.dispatch(TestAction.Search("abc"))

                // Wait for debounce (100ms from last action)
                advanceTimeBy(100)
                // "abc" debounce completes, processor starts (200ms delay)

                // Dispatch "abcd" while "abc" is still processing
                store.dispatch(TestAction.Search("abcd"))
                // "abcd" debounce starts, will complete in 100ms
                // Meanwhile "abc" is at 0ms of 200ms processing

                advanceTimeBy(100)
                // "abcd" debounce completes, takeLatest cancels "abc"
                // "abc" was at 100ms of 200ms, now canceled
                // "abcd" processor starts

                advanceTimeBy(250)
                // "abcd" completes

                // Only "abcd" should complete due to takeLatest
                val result = awaitItem()
                assertEquals(listOf("abcd"), result.values)

                // "abc" should have started but been canceled by takeLatest
                assertTrue(executionOrder.contains("start-abc"))
                assertFalse(executionOrder.contains("end-abc"))
                assertTrue(executionOrder.contains("end-abcd"))

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `chained strategy works with group`() = runTest {
            val executionOrder = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    group(debounce(100.milliseconds) then takeLatest()) {
                        on<TestAction.Fetch> { _, action ->
                            executionOrder.add("fetch-start-${action.id}")
                            delay(50)
                            executionOrder.add("fetch-end-${action.id}")
                            emit(TestAction.FetchSuccess(action.id, action.id))
                        }
                        on<TestAction.Search> { _, action ->
                            executionOrder.add("search-start-${action.query}")
                            delay(50)
                            executionOrder.add("search-end-${action.query}")
                            emit(TestAction.SearchResult(action.query, listOf(action.query)))
                        }
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

                // Dispatch Fetch, then Search before debounce completes
                // Both debounce and takeLatest should apply across the group
                store.dispatch(TestAction.Fetch("1"))
                advanceTimeBy(50)
                store.dispatch(TestAction.Search("query")) // Resets debounce, will cancel Fetch

                advanceTimeBy(200) // Wait for debounce + execution

                val result = awaitItem()
                assertEquals(listOf("query"), result.values)

                // Fetch should not have started (canceled by debounce reset)
                assertFalse(executionOrder.contains("fetch-start-1"))
                assertTrue(executionOrder.contains("search-end-query"))

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `three-way chaining with retry works`() = runTest {
            val executionOrder = mutableListOf<String>()
            var attempt = 0

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    // TIMING then CONCURRENCY then RESILIENCE
                    on<TestAction.Search>(debounce(100.milliseconds) then takeLatest() then retry(3)) { _, action ->
                        executionOrder.add("attempt-${++attempt}-${action.query}")
                        if (attempt == 1) {
                            throw RuntimeException("First attempt fails")
                        }
                        emit(TestAction.SearchResult(action.query, listOf(action.query)))
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

                // Rapid typing - debounce should delay
                store.dispatch(TestAction.Search("a"))
                advanceTimeBy(50)
                store.dispatch(TestAction.Search("ab"))
                advanceTimeBy(50)
                store.dispatch(TestAction.Search("abc"))

                // Wait for debounce (100ms from last action)
                advanceTimeBy(100)

                // "abc" executes, fails first attempt, retry succeeds
                val result = awaitItem()
                assertEquals(listOf("abc"), result.values)

                // First attempt failed, second succeeded
                assertEquals(listOf("attempt-1-abc", "attempt-2-abc"), executionOrder)

                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    class CombinedStrategyTests {

        @Test
        fun `different strategies can be used in same middleware`() = runTest {
            val fetchExecutions = mutableListOf<String>()
            val clickExecutions = mutableListOf<String>()

            val middleware = object : Middleware<TestState, TestAction> {
                override val processors = buildProcessors {
                    on<TestAction.Fetch>(takeLatest()) { state, action ->
                        delay(50)
                        fetchExecutions.add(action.id)
                        emit(TestAction.FetchSuccess(action.id, action.id))
                    }
                    on<TestAction.Click>(takeLeading()) { state, action ->
                        delay(50)
                        clickExecutions.add(action.buttonId)
                        emit(TestAction.ClickProcessed(action.buttonId))
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

                // Test takeLatest behavior
                store.dispatch(TestAction.Fetch("f1"))
                store.dispatch(TestAction.Fetch("f2"))
                advanceTimeBy(100)
                awaitItem()

                // Test takeLeading behavior
                store.dispatch(TestAction.Click("c1"))
                store.dispatch(TestAction.Click("c2"))
                advanceTimeBy(100)
                awaitItem()

                assertEquals(listOf("f2"), fetchExecutions) // Only latest
                assertEquals(listOf("c1"), clickExecutions) // Only leading

                cancelAndIgnoreRemainingEvents()
            }
        }
    }
}
