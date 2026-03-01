package io.flowdux.remote

import kotlin.time.Duration

/**
 * Events emitted during the automatic reconnection lifecycle.
 *
 * Subscribe to these events via the `onEvent` callback in [ReconnectingClientConnection]
 * for logging, UI updates, or triggering state re-synchronization.
 */
sealed interface ReconnectionEvent {
    /** A reconnection attempt is about to start. */
    data class AttemptStarted(val attempt: Int, val maxAttempts: Int, val delay: Duration) : ReconnectionEvent

    /** The connection was (re-)established successfully. */
    data class Connected(val attempt: Int) : ReconnectionEvent

    /** A single reconnection attempt failed. More attempts may follow. */
    data class AttemptFailed(val attempt: Int, val maxAttempts: Int, val cause: Throwable) : ReconnectionEvent

    /** All reconnection attempts have been exhausted. */
    data class RetriesExhausted(val maxAttempts: Int, val lastCause: Throwable?) : ReconnectionEvent
}
