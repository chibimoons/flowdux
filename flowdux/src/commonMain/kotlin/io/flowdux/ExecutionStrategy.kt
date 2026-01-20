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
    CHAINED
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
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
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
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
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
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit = { state, action ->
        val shouldExecute = mutex.withLock {
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
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit = { state, action ->
        val currentJob = currentCoroutineContext()[Job]!!

        mutex.withLock {
            pendingJob?.cancel()
            pendingJob = currentJob
        }

        delay(duration)

        val shouldExecute = mutex.withLock {
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
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit = { state, action ->
        val now = timeSource.markNow()

        val shouldExecute = mutex.withLock {
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

// Convenience factory functions

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
class ChainedStrategy(
    private val first: ExecutionStrategy,
    private val second: ExecutionStrategy
) : ExecutionStrategy {
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
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit =
        first.wrap(second.wrap(processor))
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
infix fun ExecutionStrategy.then(next: ExecutionStrategy): ExecutionStrategy =
    ChainedStrategy(this, next)
