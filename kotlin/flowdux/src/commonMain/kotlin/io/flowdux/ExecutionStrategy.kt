package io.flowdux

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Categories of execution strategies for validation during chaining.
 */
enum class StrategyCategory {
    /** Timing strategies control when to execute (debounce, throttle) */
    TIMING,

    /** Concurrency strategies control how to handle concurrent executions (takeLatest, takeLeading) */
    CONCURRENCY,

    /** Resilience strategies control how to handle failures (retry, circuitBreaker) */
    RESILIENCE,

    /** Chained strategies composed of multiple strategies */
    CHAINED,
}

/**
 * Defines how action processors should handle concurrent executions.
 */
sealed interface ExecutionStrategy {
    /**
     * The category of this strategy, used for validation during chaining.
     */
    val category: StrategyCategory

    /**
     * Creates a wrapper that applies this strategy to the given processor.
     * The wrapper manages the execution lifecycle according to the strategy.
     */
    fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit,
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit
}

/**
 * Cancels any previous execution when a new action arrives.
 * Only the latest action's result will be emitted.
 *
 * Use [group] in middleware to coordinate cancellation across different action types.
 */
class TakeLatest : ExecutionStrategy {
    override val category = StrategyCategory.CONCURRENCY

    private val mutex = Mutex()
    private var currentJob: Job? = null

    override fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit,
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit = { state, action ->
        val job = currentCoroutineContext()[Job]!!

        mutex.withLock {
            currentJob?.cancel()
            currentJob = job
        }

        try {
            processor(state, action)
        } catch (e: CancellationException) {
            throw e
        } finally {
            mutex.withLock {
                if (currentJob === job) {
                    currentJob = null
                }
            }
        }
    }
}

/**
 * Ignores new actions while one is still processing.
 * Only the first action in a series will execute.
 *
 * Use [group] in middleware to coordinate across different action types.
 */
class TakeLeading : ExecutionStrategy {
    override val category = StrategyCategory.CONCURRENCY

    private val mutex = Mutex()
    private var isActive = false

    override fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit,
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit = { state, action ->
        val shouldExecute =
            mutex.withLock {
                if (isActive) {
                    false
                } else {
                    isActive = true
                    true
                }
            }

        if (shouldExecute) {
            try {
                processor(state, action)
            } finally {
                mutex.withLock {
                    isActive = false
                }
            }
        }
    }
}

/**
 * Queues actions and processes them one at a time, preserving order.
 * Unlike [TakeLeading] which ignores new actions, this strategy waits
 * for the current action to complete before processing the next one.
 *
 * Use [group] in middleware to coordinate sequential processing across different action types.
 */
class Sequential : ExecutionStrategy {
    override val category = StrategyCategory.CONCURRENCY

    private val mutex = Mutex()

    override fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit,
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit = { state, action ->
        mutex.withLock {
            processor(state, action)
        }
    }
}

/**
 * Delays execution. If another action arrives before the delay completes,
 * the previous action is canceled and the timer restarts.
 *
 * @param duration The debounce delay duration
 */
class Debounce(private val duration: Duration) : ExecutionStrategy {
    override val category = StrategyCategory.TIMING

    private val mutex = Mutex()
    private var pendingJob: Job? = null

    override fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit,
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit = { state, action ->
        val currentJob = currentCoroutineContext()[Job]!!

        mutex.withLock {
            pendingJob?.cancel()
            pendingJob = currentJob
        }

        delay(duration)

        val shouldExecute =
            mutex.withLock {
                pendingJob === currentJob
            }

        if (shouldExecute) {
            processor(state, action)
        }
    }
}

/**
 * Limits execution rate. Executes the first action immediately,
 * then ignores subsequent actions until the time window passes.
 *
 * @param duration The throttle window duration
 */
class Throttle(private val duration: Duration) : ExecutionStrategy {
    override val category = StrategyCategory.TIMING

    private val mutex = Mutex()
    private val timeSource = TimeSource.Monotonic
    private var lastExecutionMark: TimeSource.Monotonic.ValueTimeMark? = null

    override fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit,
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit = { state, action ->
        val now = timeSource.markNow()

        val shouldExecute =
            mutex.withLock {
                val last = lastExecutionMark
                if (last == null || now - last >= duration) {
                    lastExecutionMark = now
                    true
                } else {
                    false
                }
            }

        if (shouldExecute) {
            processor(state, action)
        }
    }
}

/**
 * Retries the processor execution on failure.
 *
 * @param maxAttempts Maximum number of attempts (including the initial attempt)
 * @param retryIf Optional predicate to determine if a specific exception should trigger a retry.
 *                Defaults to retrying on all non-cancellation exceptions.
 */
class Retry(private val maxAttempts: Int, private val retryIf: (Throwable) -> Boolean = { true }) : ExecutionStrategy {
    override val category = StrategyCategory.RESILIENCE

    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    override fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit,
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit = { state, action ->
        for (attempt in 0 until maxAttempts) {
            try {
                processor(state, action)
                break // Success, exit loop
            } catch (e: CancellationException) {
                throw e // Don't retry on cancellation
            } catch (e: Throwable) {
                if (attempt == maxAttempts - 1 || !retryIf(e)) {
                    throw e // Last attempt or non-retryable exception
                }
                // Continue to next attempt
            }
        }
    }
}

/**
 * Retries the processor execution on failure with exponential backoff.
 *
 * The delay between retries for the k-th retry (k starting at 1 for the first retry after the
 * initial attempt) follows the formula: `initialDelay * (factor ^ (k - 1))`
 * with optional jitter to prevent thundering herd problems.
 *
 * @param maxAttempts Maximum number of attempts (including the initial attempt)
 * @param initialDelay The initial delay before the first retry
 * @param maxDelay Maximum delay between retries (caps the exponential growth)
 * @param factor Multiplier for exponential backoff (default: 2.0)
 * @param jitter Random jitter factor (0.0 to 1.0) to add randomness to delays (default: 0.0)
 * @param retryIf Optional predicate to determine if a specific exception should trigger a retry
 */
class RetryWithBackoff(
    private val maxAttempts: Int,
    private val initialDelay: Duration,
    private val maxDelay: Duration = Duration.INFINITE,
    private val factor: Double = 2.0,
    private val jitter: Double = 0.0,
    private val retryIf: (Throwable) -> Boolean = { true },
) : ExecutionStrategy {
    override val category = StrategyCategory.RESILIENCE

    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(factor >= 1.0) { "factor must be at least 1.0" }
        require(jitter in 0.0..1.0) { "jitter must be between 0.0 and 1.0" }
    }

    override fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit,
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit = { state, action ->
        for (attempt in 0 until maxAttempts) {
            try {
                processor(state, action)
                break // Success, exit loop
            } catch (e: CancellationException) {
                throw e // Don't retry on cancellation
            } catch (e: Throwable) {
                if (attempt == maxAttempts - 1 || !retryIf(e)) {
                    throw e // Last attempt or non-retryable exception
                }

                // Calculate delay with exponential backoff
                val baseDelay = initialDelay * factor.pow(attempt)
                val cappedDelay = minOf(baseDelay, maxDelay)

                // Apply jitter
                val jitterAmount =
                    if (jitter > 0.0) {
                        // jitter is a factor (0.0–1.0); this yields an extra delay in [0, cappedDelay * jitter]
                        cappedDelay * jitter * kotlin.random.Random.nextDouble()
                    } else {
                        Duration.ZERO
                    }

                val finalDelay = (cappedDelay + jitterAmount).coerceAtLeast(Duration.ZERO)
                delay(finalDelay)
            }
        }
    }

    private fun Double.pow(n: Int): Double {
        var result = 1.0
        repeat(n) { result *= this }
        return result
    }
}

/**
 * Allows all executions to run concurrently without any coordination.
 * Each action will execute independently regardless of other actions.
 *
 * Use this when you want multiple instances of the same action to run in parallel.
 */
class Concurrent : ExecutionStrategy {
    override val category = StrategyCategory.CONCURRENCY

    override fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit,
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit = processor
}

// Convenience factory functions

/**
 * Creates a [Concurrent] strategy that allows all executions to run in parallel.
 */
fun concurrent(): ExecutionStrategy = Concurrent()

/**
 * Creates a [TakeLatest] strategy that cancels previous executions when a new action arrives.
 *
 * Use [group] in middleware to share a strategy instance across different action types.
 */
fun takeLatest(): ExecutionStrategy = TakeLatest()

/**
 * Creates a [TakeLeading] strategy that ignores new actions while one is processing.
 *
 * Use [group] in middleware to share a strategy instance across different action types.
 */
fun takeLeading(): ExecutionStrategy = TakeLeading()

/**
 * Creates a [Sequential] strategy that queues actions and processes them one at a time.
 *
 * Unlike [takeLeading] which ignores new actions, this strategy waits for
 * the current action to complete before processing the next one in order.
 *
 * Use [group] in middleware to share a strategy instance across different action types.
 */
fun sequential(): ExecutionStrategy = Sequential()

/**
 * Creates a [Debounce] strategy that delays execution until no new actions arrive.
 *
 * @param duration The debounce delay duration
 */
fun debounce(duration: Duration): ExecutionStrategy = Debounce(duration)

/**
 * Creates a [Debounce] strategy that delays execution until no new actions arrive.
 *
 * @param timeMs The debounce delay in milliseconds
 */
fun debounce(timeMs: Long): ExecutionStrategy = Debounce(timeMs.milliseconds)

/**
 * Creates a [Throttle] strategy that limits execution rate.
 *
 * @param duration The throttle window duration
 */
fun throttle(duration: Duration): ExecutionStrategy = Throttle(duration)

/**
 * Creates a [Throttle] strategy that limits execution rate.
 *
 * @param timeMs The throttle window in milliseconds
 */
fun throttle(timeMs: Long): ExecutionStrategy = Throttle(timeMs.milliseconds)

/**
 * Creates a [Retry] strategy that retries failed executions.
 *
 * @param maxAttempts Maximum number of attempts (including the initial attempt)
 * @param retryIf Optional predicate to determine if a specific exception should trigger a retry
 */
fun retry(maxAttempts: Int, retryIf: (Throwable) -> Boolean = { true }): ExecutionStrategy = Retry(maxAttempts, retryIf)

/**
 * Creates a [RetryWithBackoff] strategy that retries failed executions with exponential backoff.
 *
 * @param maxAttempts Maximum number of attempts (including the initial attempt)
 * @param initialDelay The initial delay before the first retry
 * @param maxDelay Maximum delay between retries (caps the exponential growth)
 * @param factor Multiplier for exponential backoff (default: 2.0)
 * @param jitter Random jitter factor (0.0 to 1.0) to add randomness to delays (default: 0.0)
 * @param retryIf Optional predicate to determine if a specific exception should trigger a retry
 */
fun retryWithBackoff(
    maxAttempts: Int,
    initialDelay: Duration,
    maxDelay: Duration = Duration.INFINITE,
    factor: Double = 2.0,
    jitter: Double = 0.0,
    retryIf: (Throwable) -> Boolean = { true },
): ExecutionStrategy = RetryWithBackoff(maxAttempts, initialDelay, maxDelay, factor, jitter, retryIf)

// Strategy chaining

/**
 * A composed strategy that chains two strategies together.
 *
 * The first strategy wraps the second, meaning the first strategy's logic runs first (outer layer).
 * For example, `debounce then takeLatest` will first apply debounce delay, then takeLatest cancellation.
 *
 * @param first The outer strategy (runs first)
 * @param second The inner strategy (runs second)
 * @throws IllegalArgumentException if both strategies belong to the same category
 */
class ChainedStrategy(private val first: ExecutionStrategy, private val second: ExecutionStrategy) :
    ExecutionStrategy {
    override val category = StrategyCategory.CHAINED

    private val categories: Set<StrategyCategory>

    init {
        val firstCategories = if (first is ChainedStrategy) first.categories else setOf(first.category)
        val secondCategories = if (second is ChainedStrategy) second.categories else setOf(second.category)

        val overlap = firstCategories.intersect(secondCategories)
        require(overlap.isEmpty()) {
            "Cannot chain strategies of the same category. " +
                "Conflicting category: ${overlap.first()}. " +
                "First: ${first::class.simpleName}, Second: ${second::class.simpleName}"
        }

        categories = firstCategories + secondCategories
    }

    override fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit,
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit = first.wrap(second.wrap(processor))
}

/**
 * Chains this strategy with another strategy.
 *
 * The resulting strategy applies this strategy first (outer layer), then the next strategy.
 * For example, `debounce(300.ms) then takeLatest()` will:
 * 1. Wait for 300ms debounce period
 * 2. Then apply takeLatest cancellation logic
 *
 * Strategies of the same category cannot be chained together (e.g., two TIMING or two CONCURRENCY strategies).
 *
 * @param next The strategy to chain after this one
 * @return A new [ChainedStrategy] combining both strategies
 * @throws IllegalArgumentException if both strategies belong to the same category
 */
infix fun ExecutionStrategy.then(next: ExecutionStrategy): ExecutionStrategy = ChainedStrategy(this, next)
