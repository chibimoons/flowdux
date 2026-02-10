package io.flowdux.remote.server.usecase

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for session management, including idle timeout and cleanup settings.
 *
 * @property idleTimeout Duration after which an inactive session is considered idle.
 *   Sessions that have not received any messages for this duration may be cleaned up.
 * @property cleanupInterval Interval between automatic cleanup runs for idle sessions.
 */
data class SessionConfig(
    val idleTimeout: Duration = 5.minutes,
    val cleanupInterval: Duration = 1.minutes,
) {
    init {
        require(idleTimeout > Duration.ZERO) { "idleTimeout must be positive" }
        require(cleanupInterval > Duration.ZERO) { "cleanupInterval must be positive" }
        require(cleanupInterval <= idleTimeout) {
            "cleanupInterval should be <= idleTimeout for effective cleanup"
        }
    }

    companion object {
        /** Default configuration for typical use cases. */
        val Default = SessionConfig()

        /** Aggressive cleanup for memory-constrained environments. */
        val Aggressive = SessionConfig(
            idleTimeout = 1.minutes,
            cleanupInterval = 30.seconds,
        )

        /** Conservative configuration for long-lived connections. */
        val LongLived = SessionConfig(
            idleTimeout = 30.minutes,
            cleanupInterval = 5.minutes,
        )
    }
}

/**
 * Events emitted during session lifecycle monitoring.
 */
sealed interface SessionEvent {
    /**
     * A new session has been added.
     *
     * @property sessionId The ID of the added session.
     */
    data class Added(val sessionId: String) : SessionEvent

    /**
     * A session has been removed.
     *
     * @property sessionId The ID of the removed session.
     */
    data class Removed(val sessionId: String) : SessionEvent

    /**
     * A session has become idle (no activity for some time).
     *
     * NOTE: This event is currently not emitted by the session monitoring logic
     * and is reserved for future use.
     *
     * @property sessionId The ID of the idle session.
     * @property duration How long the session has been idle.
     */
    @Deprecated(
        message = "SessionEvent.Idle is not currently emitted by monitorSessions() and is reserved for future use.",
        level = DeprecationLevel.WARNING,
    )
    data class Idle(val sessionId: String, val duration: Duration) : SessionEvent

    /**
     * A session has timed out and will be cleaned up.
     *
     * @property sessionId The ID of the timed-out session.
     */
    data class Timeout(val sessionId: String) : SessionEvent

    /**
     * Activity was detected on a session.
     *
     * @property sessionId The ID of the session with activity.
     */
    data class Activity(val sessionId: String) : SessionEvent
}

/**
 * Result of a cleanup operation.
 *
 * @property removedCount Number of sessions removed during cleanup.
 * @property removedSessionIds List of session IDs that were removed.
 * @property remainingCount Number of sessions remaining after cleanup.
 */
data class CleanupResult(
    val removedCount: Int,
    val removedSessionIds: List<String>,
    val remainingCount: Int,
)
