package io.flowdux.remote.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResponseCollectorTest {

    @Test
    fun `collects actions on state reduced`() {
        val collector = ResponseCollector<ServerState, ServerAction>()

        collector.onStateReduced(ServerAction.Increment, ServerState(0), ServerState(1))
        collector.onStateReduced(ServerAction.Add(5), ServerState(1), ServerState(6))

        val drained = collector.drain()
        assertEquals(2, drained.size)
        assertEquals(ServerAction.Increment, drained[0])
        assertEquals(ServerAction.Add(5), drained[1])
    }

    @Test
    fun `drain clears pending actions`() {
        val collector = ResponseCollector<ServerState, ServerAction>()

        collector.onStateReduced(ServerAction.Increment, ServerState(0), ServerState(1))
        assertEquals(1, collector.drain().size)
        assertEquals(0, collector.drain().size)
    }

    @Test
    fun `drain returns empty list when nothing collected`() {
        val collector = ResponseCollector<ServerState, ServerAction>()
        assertEquals(emptyList<ServerAction>(), collector.drain())
    }
}
