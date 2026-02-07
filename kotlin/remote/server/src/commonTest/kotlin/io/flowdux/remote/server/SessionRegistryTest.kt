package io.flowdux.remote.server

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SessionRegistry] interface contract, using [InMemorySessionRegistry].
 */
class SessionRegistryTest {

    @Test
    fun `empty registry has zero count and empty ids`() = runTest {
        val registry = InMemorySessionRegistry<ServerAction>()

        assertEquals(0, registry.sessionCount())
        assertTrue(registry.sessionIds().isEmpty())
        assertTrue(registry.getSessions().isEmpty())
    }

    @Test
    fun `addSession registers a session`() = runTest {
        val registry = InMemorySessionRegistry<ServerAction>()
        val connection = MockTypedServerConnection<ServerAction>()

        registry.addSession("session-1", connection)

        assertEquals(1, registry.sessionCount())
        assertTrue(registry.sessionIds().contains("session-1"))
        assertEquals(connection, registry.getSession("session-1"))
    }

    @Test
    fun `addSession overwrites existing session with same id`() = runTest {
        val registry = InMemorySessionRegistry<ServerAction>()
        val connection1 = MockTypedServerConnection<ServerAction>()
        val connection2 = MockTypedServerConnection<ServerAction>()

        registry.addSession("session-1", connection1)
        registry.addSession("session-1", connection2)

        assertEquals(1, registry.sessionCount())
        assertEquals(connection2, registry.getSession("session-1"))
    }

    @Test
    fun `removeSession unregisters a session`() = runTest {
        val registry = InMemorySessionRegistry<ServerAction>()
        val connection = MockTypedServerConnection<ServerAction>()

        registry.addSession("session-1", connection)
        registry.removeSession("session-1")

        assertEquals(0, registry.sessionCount())
        assertTrue(registry.sessionIds().isEmpty())
        assertNull(registry.getSession("session-1"))
    }

    @Test
    fun `removeSession is no-op for non-existent session`() = runTest {
        val registry = InMemorySessionRegistry<ServerAction>()

        registry.removeSession("non-existent")

        assertEquals(0, registry.sessionCount())
    }

    @Test
    fun `getSession returns null for non-existent session`() = runTest {
        val registry = InMemorySessionRegistry<ServerAction>()

        assertNull(registry.getSession("non-existent"))
    }

    @Test
    fun `getSessions returns snapshot of all sessions`() = runTest {
        val registry = InMemorySessionRegistry<ServerAction>()
        val connection1 = MockTypedServerConnection<ServerAction>()
        val connection2 = MockTypedServerConnection<ServerAction>()

        registry.addSession("session-1", connection1)
        registry.addSession("session-2", connection2)

        val sessions = registry.getSessions()

        assertEquals(2, sessions.size)
        assertEquals(connection1, sessions["session-1"])
        assertEquals(connection2, sessions["session-2"])
    }

    @Test
    fun `sessionIds returns snapshot that is not affected by later changes`() = runTest {
        val registry = InMemorySessionRegistry<ServerAction>()
        val connection = MockTypedServerConnection<ServerAction>()

        registry.addSession("session-1", connection)
        val snapshot = registry.sessionIds()

        registry.addSession("session-2", connection)

        assertEquals(1, snapshot.size)
        assertTrue(snapshot.contains("session-1"))
    }

    @Test
    fun `getSessions returns snapshot that is not affected by later changes`() = runTest {
        val registry = InMemorySessionRegistry<ServerAction>()
        val connection1 = MockTypedServerConnection<ServerAction>()
        val connection2 = MockTypedServerConnection<ServerAction>()

        registry.addSession("session-1", connection1)
        val snapshot = registry.getSessions()

        registry.addSession("session-2", connection2)

        assertEquals(1, snapshot.size)
        assertEquals(connection1, snapshot["session-1"])
    }
}
