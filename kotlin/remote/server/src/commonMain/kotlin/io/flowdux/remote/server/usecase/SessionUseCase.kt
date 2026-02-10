package io.flowdux.remote.server.usecase

import io.flowdux.Action
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.session.SessionBroadcaster
import io.flowdux.remote.server.session.SessionRegistry
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Use case for managing server-side session lifecycle, including broadcast and cleanup.
 *
 * This abstraction separates session business logic from the middleware,
 * making both easier to test and maintain.
 *
 * Typical usage:
 * ```kotlin
 * val sessionUseCase = SessionUseCaseImpl(
 *     registry = InMemorySessionRegistry(),
 *     broadcaster = SessionBroadcaster(registry),
 *     config = SessionConfig(
 *         idleTimeout = 5.minutes,
 *         cleanupInterval = 1.minutes,
 *     ),
 * )
 *
 * // In middleware:
 * sessionUseCase.addSession(sessionId, connection)
 * sessionUseCase.broadcast(action)
 * ```
 *
 * @param A The action type.
 */
interface SessionUseCase<A : Action> {
    /**
     * Add a new session with the given connection.
     *
     * @param sessionId Unique identifier for this session.
     * @param connection Typed connection for communicating with this client.
     */
    suspend fun addSession(sessionId: String, connection: TypedServerConnection<A>)

    /**
     * Remove a session.
     *
     * @param sessionId The session ID to remove.
     */
    suspend fun removeSession(sessionId: String)

    /**
     * Get a snapshot of all connected session IDs.
     *
     * @return Set of session IDs currently connected.
     */
    suspend fun sessionIds(): Set<String>

    /**
     * Get the number of currently connected sessions.
     *
     * @return Session count.
     */
    suspend fun sessionCount(): Int

    /**
     * Broadcast an action to all connected clients.
     *
     * Individual connection failures are isolated and do not affect others.
     *
     * @param action The action to broadcast.
     */
    suspend fun broadcast(action: A)

    /**
     * Send an action to a specific client.
     *
     * No-op if the session does not exist.
     *
     * @param sessionId The target session ID.
     * @param action The action to send.
     */
    suspend fun sendToClient(sessionId: String, action: A)

    /**
     * Monitor session lifecycle events.
     *
     * Emits [SessionEvent] for session additions, removals, idle detection, and timeouts.
     *
     * @return A flow of session events.
     */
    fun monitorSessions(): Flow<SessionEvent>

    /**
     * Clean up idle sessions based on the configured timeout.
     *
     * @return [CleanupResult] containing information about cleaned up sessions.
     */
    suspend fun cleanupIdleSessions(): CleanupResult

    /**
     * Record activity for a session.
     *
     * Call this when a session sends a message to reset its idle timer.
     *
     * @param sessionId The session that had activity.
     */
    suspend fun recordActivity(sessionId: String)
}

/**
 * Default implementation of [SessionUseCase].
 *
 * Wraps a [SessionRegistry] and [SessionBroadcaster] and adds idle timeout tracking.
 *
 * @param A The action type.
 * @param registry The session registry for storing connections.
 * @param broadcaster The broadcaster for sending actions to clients.
 * @param config Configuration for session management.
 * @param timeSource Time source for idle tracking (injectable for testing).
 */
class SessionUseCaseImpl<A : Action>(
    private val registry: SessionRegistry<A>,
    private val broadcaster: SessionBroadcaster<A>,
    private val config: SessionConfig = SessionConfig(),
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : SessionUseCase<A> {

    private val mutex = Mutex()
    private val sessionActivity = mutableMapOf<String, ComparableTimeMark>()
    private val eventFlow = MutableSharedFlow<SessionEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override suspend fun addSession(sessionId: String, connection: TypedServerConnection<A>) {
        mutex.withLock {
            registry.addSession(sessionId, connection)
            sessionActivity[sessionId] = timeSource.markNow()
        }
        eventFlow.emit(SessionEvent.Added(sessionId))
    }

    override suspend fun removeSession(sessionId: String) {
        val removed = mutex.withLock {
            registry.removeSession(sessionId)
            sessionActivity.remove(sessionId) != null
        }
        if (removed) {
            eventFlow.emit(SessionEvent.Removed(sessionId))
        }
    }

    override suspend fun sessionIds(): Set<String> {
        return registry.sessionIds()
    }

    override suspend fun sessionCount(): Int {
        return registry.sessionCount()
    }

    override suspend fun broadcast(action: A) {
        broadcaster.broadcast(action)
    }

    override suspend fun sendToClient(sessionId: String, action: A) {
        broadcaster.sendToClient(sessionId, action)
    }

    override fun monitorSessions(): Flow<SessionEvent> = eventFlow.asSharedFlow()

    override suspend fun cleanupIdleSessions(): CleanupResult {
        val now = timeSource.markNow()
        val removedIds = mutableListOf<String>()

        mutex.withLock {
            val sessionsToRemove = sessionActivity.filter { (_, lastActivity) ->
                now - lastActivity >= config.idleTimeout
            }.keys.toList()

            for (sessionId in sessionsToRemove) {
                registry.removeSession(sessionId)
                sessionActivity.remove(sessionId)
                removedIds.add(sessionId)
            }
        }

        for (sessionId in removedIds) {
            eventFlow.emit(SessionEvent.Timeout(sessionId))
            eventFlow.emit(SessionEvent.Removed(sessionId))
        }

        val remaining = registry.sessionCount()

        return CleanupResult(
            removedCount = removedIds.size,
            removedSessionIds = removedIds,
            remainingCount = remaining,
        )
    }

    override suspend fun recordActivity(sessionId: String) {
        val now = timeSource.markNow()
        val hadPreviousActivity: Boolean
        mutex.withLock {
            hadPreviousActivity = sessionActivity.containsKey(sessionId)
            if (hadPreviousActivity) {
                sessionActivity[sessionId] = now
            }
        }
        if (hadPreviousActivity) {
            eventFlow.emit(SessionEvent.Activity(sessionId))
        }
    }

    /**
     * Get the idle duration for a session.
     *
     * @param sessionId The session to check.
     * @return The idle duration, or null if session not found.
     */
    suspend fun getIdleDuration(sessionId: String): Duration? {
        return mutex.withLock {
            sessionActivity[sessionId]?.let { lastActivity ->
                timeSource.markNow() - lastActivity
            }
        }
    }

    /**
     * Check if a session is idle (exceeded idle timeout).
     *
     * @param sessionId The session to check.
     * @return True if idle, false otherwise or if session not found.
     */
    suspend fun isSessionIdle(sessionId: String): Boolean {
        val duration = getIdleDuration(sessionId) ?: return false
        return duration >= config.idleTimeout
    }
}

/**
 * Extension function to start automatic cleanup in a coroutine scope.
 *
 * @param scope The coroutine scope to launch cleanup job in.
 * @param onCleanup Optional callback after each cleanup run.
 * @return The job that can be cancelled to stop automatic cleanup.
 */
fun <A : Action> SessionUseCase<A>.startAutoCleanupJob(
    scope: CoroutineScope,
    config: SessionConfig = SessionConfig(),
    onCleanup: (suspend (CleanupResult) -> Unit)? = null,
): Job = scope.launch {
    while (true) {
        delay(config.cleanupInterval)
        val result = cleanupIdleSessions()
        onCleanup?.invoke(result)
    }
}

/**
 * Extension function to start session monitoring in a coroutine scope.
 *
 * @param scope The coroutine scope to launch monitoring in.
 * @param onEvent Callback for each session event.
 * @return The job that can be cancelled to stop monitoring.
 */
fun <A : Action> SessionUseCase<A>.startMonitoringJob(
    scope: CoroutineScope,
    onEvent: suspend (SessionEvent) -> Unit,
): Job = scope.launch {
    monitorSessions().collect { event ->
        onEvent(event)
    }
}
