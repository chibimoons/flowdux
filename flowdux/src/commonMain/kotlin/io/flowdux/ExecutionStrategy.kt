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
 * Cancels any previous execution when a new action arrives.
 * Only the latest action's result will be emitted.
 *
 * Use [group] in middleware to coordinate cancellation across different action types.
 */
class TakeLatest : ExecutionStrategy {
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
