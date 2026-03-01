package io.flowdux.remote

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for automatic reconnection with exponential backoff.
 *
 * @param maxAttempts Maximum number of consecutive reconnection attempts before giving up.
 *        Set to [Int.MAX_VALUE] for unlimited retries.
 * @param initialDelay Delay before the first reconnection attempt.
 * @param maxDelay Upper bound for the backoff delay. The delay never exceeds this value.
 * @param factor Multiplier applied to the delay after each failed attempt.
 * @param jitterFactor Random jitter factor (0.0–1.0) to avoid thundering-herd reconnections.
 *        The actual delay is `delay * (1 - random(0, jitterFactor))`.
 */
data class ReconnectionConfig(
    val maxAttempts: Int = 5,
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
    val factor: Double = 2.0,
    val jitterFactor: Double = 0.1,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive, was $maxAttempts" }
        require(initialDelay > Duration.ZERO) { "initialDelay must be positive, was $initialDelay" }
        require(maxDelay >= initialDelay) { "maxDelay ($maxDelay) must be >= initialDelay ($initialDelay)" }
        require(factor >= 1.0) { "factor must be >= 1.0, was $factor" }
        require(jitterFactor in 0.0..1.0) { "jitterFactor must be in 0.0..1.0, was $jitterFactor" }
    }

    /**
     * Calculate the delay for the given [attempt] (0-based) with optional jitter.
     *
     * @return delay duration, always at least 1 millisecond to prevent tight retry loops.
     */
    internal fun delayForAttempt(attempt: Int, random: () -> Double = { kotlin.random.Random.nextDouble() }): Duration {
        var delay = initialDelay
        repeat(attempt) {
            delay = (delay * factor).coerceAtMost(maxDelay)
        }
        val jitter = delay * jitterFactor * random()
        return (delay - jitter).coerceAtLeast(1.milliseconds)
    }
}
