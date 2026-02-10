package io.flowdux.remote.server.usecase

import app.cash.turbine.test
import io.flowdux.remote.server.MockTypedServerConnection
import io.flowdux.remote.server.ServerAction
import io.flowdux.remote.server.session.InMemorySessionRegistry
import io.flowdux.remote.server.session.SessionBroadcaster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class SessionUseCaseTest {

    private fun createSessionUseCase(
        config: SessionConfig = SessionConfig(),
        timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    ): Pair<SessionUseCaseImpl<ServerAction>, SessionBroadcaster<ServerAction>> {
        val registry = InMemorySessionRegistry<ServerAction>()
        val broadcaster = SessionBroadcaster(registry)
        val useCase = SessionUseCaseImpl(registry, broadcaster, config, timeSource)
        return useCase to broadcaster
    }

    @Test
    fun `addSession registers session and emits Added event`() = runTest {
        val (useCase, _) = createSessionUseCase()
        val connection = MockTypedServerConnection<ServerAction>()

        useCase.monitorSessions().test {
            useCase.addSession("session-1", connection)

            val event = awaitItem()
            assertIs<SessionEvent.Added>(event)
            assertEquals("session-1", event.sessionId)

            assertEquals(setOf("session-1"), useCase.sessionIds())
            assertEquals(1, useCase.sessionCount())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removeSession unregisters session and emits Removed event`() = runTest {
        val (useCase, _) = createSessionUseCase()
        val connection = MockTypedServerConnection<ServerAction>()

        useCase.addSession("session-1", connection)

        useCase.monitorSessions().test {
            useCase.removeSession("session-1")

            val event = awaitItem()
            assertIs<SessionEvent.Removed>(event)
            assertEquals("session-1", event.sessionId)

            assertEquals(emptySet(), useCase.sessionIds())
            assertEquals(0, useCase.sessionCount())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `broadcast sends action to all sessions`() = runTest {
        val (useCase, _) = createSessionUseCase()
        val connection1 = MockTypedServerConnection<ServerAction>()
        val connection2 = MockTypedServerConnection<ServerAction>()

        useCase.addSession("session-1", connection1)
        useCase.addSession("session-2", connection2)

        useCase.broadcast(ServerAction.Add(42))

        assertEquals(listOf<ServerAction>(ServerAction.Add(42)), connection1.sentActions)
        assertEquals(listOf<ServerAction>(ServerAction.Add(42)), connection2.sentActions)
    }

    @Test
    fun `sendToClient sends action to specific session`() = runTest {
        val (useCase, _) = createSessionUseCase()
        val connection1 = MockTypedServerConnection<ServerAction>()
        val connection2 = MockTypedServerConnection<ServerAction>()

        useCase.addSession("session-1", connection1)
        useCase.addSession("session-2", connection2)

        useCase.sendToClient("session-1", ServerAction.Add(42))

        assertEquals(listOf<ServerAction>(ServerAction.Add(42)), connection1.sentActions)
        assertEquals(emptyList<ServerAction>(), connection2.sentActions)
    }

    @Test
    fun `sendToClient is no-op for non-existent session`() = runTest {
        val (useCase, _) = createSessionUseCase()
        val connection = MockTypedServerConnection<ServerAction>()

        useCase.addSession("session-1", connection)

        // This should not throw
        useCase.sendToClient("non-existent", ServerAction.Add(42))

        assertEquals(emptyList<ServerAction>(), connection.sentActions)
    }

    @Test
    fun `recordActivity updates session timestamp and emits Activity event`() = runTest {
        val (useCase, _) = createSessionUseCase()
        val connection = MockTypedServerConnection<ServerAction>()

        useCase.monitorSessions().test {
            // Add session - will emit Added event
            useCase.addSession("session-1", connection)
            assertIs<SessionEvent.Added>(awaitItem())

            // Record activity - will emit Activity event
            useCase.recordActivity("session-1")
            val event = awaitItem()
            assertIs<SessionEvent.Activity>(event)
            assertEquals("session-1", event.sessionId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `recordActivity is no-op for non-existent session`() = runTest {
        val (useCase, _) = createSessionUseCase()
        val connection = MockTypedServerConnection<ServerAction>()

        useCase.monitorSessions().test {
            // Add session - will emit Added event
            useCase.addSession("session-1", connection)
            assertIs<SessionEvent.Added>(awaitItem())

            // This should not emit any event
            useCase.recordActivity("non-existent")

            // Brief wait to ensure no event is emitted
            delay(50)
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cleanupIdleSessions removes sessions past idle timeout`() = runTest {
        val timeSource = TestTimeSource()
        val config = SessionConfig(
            idleTimeout = 1.minutes,
            cleanupInterval = 30.seconds,
        )
        val (useCase, _) = createSessionUseCase(config, timeSource)

        val connection1 = MockTypedServerConnection<ServerAction>()
        val connection2 = MockTypedServerConnection<ServerAction>()

        useCase.addSession("session-1", connection1)
        useCase.addSession("session-2", connection2)

        assertEquals(2, useCase.sessionCount())

        // Advance time past idle timeout
        timeSource += 2.minutes

        useCase.monitorSessions().test {
            val result = useCase.cleanupIdleSessions()

            assertEquals(2, result.removedCount)
            assertTrue(result.removedSessionIds.containsAll(listOf("session-1", "session-2")))
            assertEquals(0, result.remainingCount)

            // Should emit Timeout and Removed events for both sessions
            val events = mutableListOf<SessionEvent>()
            repeat(4) { // 2 timeouts + 2 removals
                events.add(awaitItem())
            }

            val timeoutEvents = events.filterIsInstance<SessionEvent.Timeout>()
            val removedEvents = events.filterIsInstance<SessionEvent.Removed>()

            assertEquals(2, timeoutEvents.size)
            assertEquals(2, removedEvents.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cleanupIdleSessions keeps active sessions`() = runTest {
        val timeSource = TestTimeSource()
        val config = SessionConfig(
            idleTimeout = 1.minutes,
            cleanupInterval = 30.seconds,
        )
        val (useCase, _) = createSessionUseCase(config, timeSource)

        val connection1 = MockTypedServerConnection<ServerAction>()
        val connection2 = MockTypedServerConnection<ServerAction>()

        useCase.addSession("session-1", connection1)
        useCase.addSession("session-2", connection2)

        // Advance time partway
        timeSource += 30.seconds

        // Record activity for session-2, resetting its timer
        useCase.recordActivity("session-2")

        // Advance time past idle timeout for session-1 but not session-2
        timeSource += 40.seconds

        val result = useCase.cleanupIdleSessions()

        assertEquals(1, result.removedCount)
        assertEquals(listOf("session-1"), result.removedSessionIds)
        assertEquals(1, result.remainingCount)

        assertEquals(setOf("session-2"), useCase.sessionIds())
    }

    @Test
    fun `getIdleDuration returns correct duration`() = runTest {
        val timeSource = TestTimeSource()
        val (useCase, _) = createSessionUseCase(timeSource = timeSource)

        val connection = MockTypedServerConnection<ServerAction>()
        useCase.addSession("session-1", connection)

        // Initially, idle duration should be very small (close to 0)
        val initialDuration = useCase.getIdleDuration("session-1")
        assertTrue(initialDuration != null && initialDuration < 100.milliseconds)

        // Advance time
        timeSource += 5.minutes

        val laterDuration = useCase.getIdleDuration("session-1")
        assertTrue(laterDuration != null && laterDuration >= 5.minutes)
    }

    @Test
    fun `isSessionIdle returns true for idle sessions`() = runTest {
        val timeSource = TestTimeSource()
        val config = SessionConfig(idleTimeout = 1.minutes)
        val (useCase, _) = createSessionUseCase(config, timeSource)

        val connection = MockTypedServerConnection<ServerAction>()
        useCase.addSession("session-1", connection)

        // Not idle yet
        assertEquals(false, useCase.isSessionIdle("session-1"))

        // Advance time past idle timeout
        timeSource += 2.minutes

        // Now idle
        assertEquals(true, useCase.isSessionIdle("session-1"))
    }

    @Test
    fun `isSessionIdle returns false for non-existent session`() = runTest {
        val (useCase, _) = createSessionUseCase()

        assertEquals(false, useCase.isSessionIdle("non-existent"))
    }

    @Test
    fun `multiple sessions managed correctly`() = runTest {
        val (useCase, _) = createSessionUseCase()

        val connections = (1..5).map { MockTypedServerConnection<ServerAction>() }
        connections.forEachIndexed { index, connection ->
            useCase.addSession("session-$index", connection)
        }

        assertEquals(5, useCase.sessionCount())
        assertEquals((0..4).map { "session-$it" }.toSet(), useCase.sessionIds())

        // Remove some
        useCase.removeSession("session-1")
        useCase.removeSession("session-3")

        assertEquals(3, useCase.sessionCount())
        assertEquals(setOf("session-0", "session-2", "session-4"), useCase.sessionIds())

        // Broadcast
        useCase.broadcast(ServerAction.Add(100))

        assertEquals(0, connections[1].sentActions.size) // removed
        assertEquals(0, connections[3].sentActions.size) // removed
        assertEquals(1, connections[0].sentActions.size)
        assertEquals(1, connections[2].sentActions.size)
        assertEquals(1, connections[4].sentActions.size)
    }

    @Test
    fun `monitorSessions receives all lifecycle events`() = runTest {
        val timeSource = TestTimeSource()
        val config = SessionConfig(idleTimeout = 1.minutes)
        val (useCase, _) = createSessionUseCase(config, timeSource)

        useCase.monitorSessions().test {
            val connection = MockTypedServerConnection<ServerAction>()

            // Add session
            useCase.addSession("session-1", connection)
            val addedEvent = awaitItem()
            assertIs<SessionEvent.Added>(addedEvent)
            assertEquals("session-1", addedEvent.sessionId)

            // Record activity
            useCase.recordActivity("session-1")
            val activityEvent = awaitItem()
            assertIs<SessionEvent.Activity>(activityEvent)
            assertEquals("session-1", activityEvent.sessionId)

            // Advance time and cleanup
            timeSource += 2.minutes
            useCase.cleanupIdleSessions()

            val timeoutEvent = awaitItem()
            assertIs<SessionEvent.Timeout>(timeoutEvent)
            assertEquals("session-1", timeoutEvent.sessionId)

            val removedEvent = awaitItem()
            assertIs<SessionEvent.Removed>(removedEvent)
            assertEquals("session-1", removedEvent.sessionId)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
