package io.flowdux.remote.server

import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.session.BroadcastConfig
import io.flowdux.remote.server.session.InMemorySessionRegistry
import io.flowdux.remote.server.session.SessionBroadcaster
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionBroadcasterTest {
    private fun failingConnection(id: String = "failing"): TypedServerConnection<ServerAction> =
        object : TypedServerConnection<ServerAction> {
            override val isActive: Boolean = true
            override val incoming = emptyFlow<ServerAction>()

            override suspend fun send(action: ServerAction): Unit = throw RuntimeException("Connection $id failed")
        }

    @Test
    fun `onSendError invoked with correct sessionId and exception on sendToClient`() = runTest {
        val errors = mutableListOf<Pair<String, Exception>>()
        val registry = InMemorySessionRegistry<ServerAction>()
        val broadcaster =
            SessionBroadcaster(
                registry = registry,
                onSendError = { sessionId, e -> errors.add(sessionId to e) },
            )

        registry.addSession("session-1", failingConnection())

        broadcaster.sendToClient("session-1", ServerAction.Increment)

        assertEquals(1, errors.size)
        assertEquals("session-1", errors[0].first)
        assertTrue(errors[0].second.message!!.contains("failed"))
    }

    @Test
    fun `onSendError invoked for each failing connection during broadcast`() = runTest {
        val errors = mutableListOf<Pair<String, Exception>>()
        val registry = InMemorySessionRegistry<ServerAction>()
        val broadcaster =
            SessionBroadcaster(
                registry = registry,
                config = BroadcastConfig.Sequential,
                onSendError = { sessionId, e -> errors.add(sessionId to e) },
            )

        registry.addSession("fail-1", failingConnection("fail-1"))
        registry.addSession("fail-2", failingConnection("fail-2"))

        broadcaster.broadcast(ServerAction.Increment)

        assertEquals(2, errors.size)
        val sessionIds = errors.map { it.first }.toSet()
        assertTrue(sessionIds.contains("fail-1"))
        assertTrue(sessionIds.contains("fail-2"))
    }

    @Test
    fun `onSendError invoked during sendPerSession`() = runTest {
        val errors = mutableListOf<Pair<String, Exception>>()
        val registry = InMemorySessionRegistry<ServerAction>()
        val broadcaster =
            SessionBroadcaster(
                registry = registry,
                onSendError = { sessionId, e -> errors.add(sessionId to e) },
            )

        registry.addSession("session-x", failingConnection())

        broadcaster.sendPerSession { ServerAction.Increment }

        assertEquals(1, errors.size)
        assertEquals("session-x", errors[0].first)
    }

    @Test
    fun `null onSendError does not throw`() = runTest {
        val registry = InMemorySessionRegistry<ServerAction>()
        val broadcaster =
            SessionBroadcaster(
                registry = registry,
                onSendError = null,
            )

        registry.addSession("session-1", failingConnection())

        // Should not crash
        broadcaster.sendToClient("session-1", ServerAction.Increment)
        broadcaster.broadcast(ServerAction.Increment)
        broadcaster.sendPerSession { ServerAction.Increment }
    }

    @Test
    fun `onSendError not called for successful sends`() = runTest {
        val errors = mutableListOf<Pair<String, Exception>>()
        val goodConn = MockTypedServerConnection<ServerAction>()
        val registry = InMemorySessionRegistry<ServerAction>()
        val broadcaster =
            SessionBroadcaster(
                registry = registry,
                onSendError = { sessionId, e -> errors.add(sessionId to e) },
            )

        registry.addSession("good-session", goodConn)

        broadcaster.sendToClient("good-session", ServerAction.Increment)
        broadcaster.broadcast(ServerAction.Increment)

        assertTrue(errors.isEmpty())
        assertEquals(2, goodConn.sentActions.size)
    }
}
