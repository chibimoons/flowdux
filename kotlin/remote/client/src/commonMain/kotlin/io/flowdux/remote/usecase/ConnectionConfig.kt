package io.flowdux.remote.usecase

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for connection management, including reconnection and health check settings.
 *
 * @property reconnectMaxAttempts Maximum number of reconnection attempts before giving up.
 *   Set to 0 for infinite retries.
 * @property reconnectInitialDelay Initial delay before first reconnection attempt.
 * @property reconnectMaxDelay Maximum delay between reconnection attempts (caps exponential growth).
 * @property reconnectMultiplier Multiplier for exponential backoff (delay * multiplier after each attempt).
 * @property reconnectJitter Random jitter factor (0.0-1.0) to add to delays to prevent thundering herd.
 * @property healthCheckInterval Interval between health check pings.
 * @property healthCheckTimeout Timeout for health check response.
 */
data class ConnectionConfig(
    val reconnectMaxAttempts: Int = 5,
    val reconnectInitialDelay: Duration = 1.seconds,
    val reconnectMaxDelay: Duration = 30.seconds,
    val reconnectMultiplier: Double = 2.0,
    val reconnectJitter: Double = 0.1,
    val healthCheckInterval: Duration = 30.seconds,
    val healthCheckTimeout: Duration = 5.seconds,
) {
    init {
        require(reconnectMaxAttempts >= 0) { "reconnectMaxAttempts must be non-negative" }
        require(reconnectInitialDelay >= Duration.ZERO) { "reconnectInitialDelay must be non-negative" }
        require(reconnectMaxDelay >= reconnectInitialDelay) { "reconnectMaxDelay must be >= reconnectInitialDelay" }
        require(reconnectMultiplier >= 1.0) { "reconnectMultiplier must be >= 1.0" }
        require(reconnectJitter in 0.0..1.0) { "reconnectJitter must be in 0.0..1.0" }
        require(healthCheckInterval > Duration.ZERO) { "healthCheckInterval must be positive" }
        require(healthCheckTimeout > Duration.ZERO) { "healthCheckTimeout must be positive" }
    }

    companion object {
        /** Default configuration with moderate reconnection settings. */
        val Default = ConnectionConfig()

        /** Aggressive reconnection for low-latency requirements. */
        val Aggressive = ConnectionConfig(
            reconnectMaxAttempts = 10,
            reconnectInitialDelay = 100.milliseconds,
            reconnectMaxDelay = 5.seconds,
            reconnectMultiplier = 1.5,
            reconnectJitter = 0.2,
            healthCheckInterval = 10.seconds,
            healthCheckTimeout = 3.seconds,
        )

        /** Conservative reconnection for bandwidth-constrained environments. */
        val Conservative = ConnectionConfig(
            reconnectMaxAttempts = 3,
            reconnectInitialDelay = 5.seconds,
            reconnectMaxDelay = 60.seconds,
            reconnectMultiplier = 3.0,
            reconnectJitter = 0.3,
            healthCheckInterval = 60.seconds,
            healthCheckTimeout = 10.seconds,
        )

        /** Infinite retries with aggressive backoff. */
        val Persistent = ConnectionConfig(
            reconnectMaxAttempts = 0, // infinite
            reconnectInitialDelay = 1.seconds,
            reconnectMaxDelay = 120.seconds,
            reconnectMultiplier = 2.0,
            reconnectJitter = 0.15,
        )
    }
}

/**
 * Events emitted during connection state monitoring.
 */
sealed interface ConnectionEvent {
    /**
     * Connection state has changed.
     *
     * @property from Previous connection state.
     * @property to New connection state.
     */
    data class StateChanged(
        val from: io.flowdux.remote.ConnectionState,
        val to: io.flowdux.remote.ConnectionState,
    ) : ConnectionEvent

    /** Connection was unexpectedly lost. */
    data object ConnectionLost : ConnectionEvent

    /** Connection has been restored after being lost. */
    data object ConnectionRestored : ConnectionEvent
}

/**
 * States during a reconnection attempt sequence.
 */
sealed interface ReconnectState {
    /** No reconnection in progress. */
    data object Idle : ReconnectState

    /**
     * Currently attempting to reconnect.
     *
     * @property attempt Current attempt number (1-based).
     * @property maxAttempts Maximum attempts configured (0 = infinite).
     * @property delayMs Delay before this attempt in milliseconds.
     */
    data class Attempting(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
    ) : ReconnectState

    /** Successfully reconnected. */
    data object Connected : ReconnectState

    /**
     * Reconnection failed after all attempts exhausted.
     *
     * @property lastError The last error encountered.
     * @property attempts Total number of attempts made.
     */
    data class Failed(
        val lastError: Throwable,
        val attempts: Int,
    ) : ReconnectState
}

/**
 * Result of a health check operation.
 */
sealed interface HealthCheckResult {
    /**
     * Health check succeeded.
     *
     * @property latencyMs Round-trip latency in milliseconds.
     */
    data class Healthy(val latencyMs: Long) : HealthCheckResult

    /**
     * Health check failed due to timeout.
     *
     * @property timeoutMs The configured timeout in milliseconds.
     */
    data class Timeout(val timeoutMs: Long) : HealthCheckResult

    /**
     * Health check failed due to an error.
     *
     * @property error The error that occurred.
     */
    data class Error(val error: Throwable) : HealthCheckResult
}

/**
 * Result of a connection attempt.
 */
sealed interface ConnectionResult {
    /** Connection established successfully. */
    data object Success : ConnectionResult

    /**
     * Connection failed.
     *
     * @property error The error that occurred.
     */
    data class Failure(val error: Throwable) : ConnectionResult
}
