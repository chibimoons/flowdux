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
 * Defines how action processors should handle concurrent executions.
 */
sealed interface ExecutionStrategy {
    /**
     * Creates a wrapper that applies this strategy to the given processor.
     * The wrapper manages the execution lifecycle according to the strategy.
     */
    fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit
}

/**
 * Cancels any previous execution with the same key when a new action arrives.
 * Only the latest action's result will be emitted.
 *
 * @param key Grouping key - actions with the same key cancel each other
 */
class TakeLatest(private val key: Any) : ExecutionStrategy {
    private val mutex = Mutex()
    private val jobs = mutableMapOf<Any, Job>()

    override fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit = { state, action ->
        val currentJob = currentCoroutineContext()[Job]!!

        mutex.withLock {
            jobs[key]?.cancel()
            jobs[key] = currentJob
        }

        try {
            processor(state, action)
        } catch (e: CancellationException) {
            throw e
        } finally {
            mutex.withLock {
                if (jobs[key] === currentJob) {
                    jobs.remove(key)
                }
            }
        }
    }
}

/**
 * Ignores new actions while one with the same key is still processing.
 * Only the first action in a series will execute.
 *
 * @param key Grouping key - actions with the same key are deduplicated
 */
class TakeLeading(private val key: Any) : ExecutionStrategy {
    private val mutex = Mutex()
    private val activeKeys = mutableSetOf<Any>()

    override fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit = { state, action ->
        val shouldExecute = mutex.withLock {
            if (key in activeKeys) {
                false
            } else {
                activeKeys.add(key)
                true
            }
        }

        if (shouldExecute) {
            try {
                processor(state, action)
            } finally {
                mutex.withLock {
                    activeKeys.remove(key)
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
 * @param key Grouping key - actions with the same key cancel each other. Defaults to Unit.
 */
fun takeLatest(key: Any = Unit): ExecutionStrategy = TakeLatest(key)

/**
 * Creates a [TakeLeading] strategy that ignores new actions while one is processing.
 *
 * @param key Grouping key - actions with the same key are deduplicated. Defaults to Unit.
 */
fun takeLeading(key: Any = Unit): ExecutionStrategy = TakeLeading(key)

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
