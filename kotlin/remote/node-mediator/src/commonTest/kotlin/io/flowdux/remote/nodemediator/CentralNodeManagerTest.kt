package io.flowdux.remote.nodemediator

import io.flowdux.Action
import io.flowdux.remote.server.connection.TypedServerConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CentralNodeManagerTest {

    @Serializable
    sealed interface TestAction : Action {
        @Serializable data class Message(val text: String) : TestAction
        @Serializable data class Ping(val id: Int) : TestAction
    }

    private class FakeTypedServerConnection : TypedServerConnection<NodeAction<TestAction>> {
        val incomingFlow = MutableSharedFlow<NodeAction<TestAction>>(extraBufferCapacity = 64)
        val sentActions = mutableListOf<NodeAction<TestAction>>()

        override val incoming: Flow<NodeAction<TestAction>> = incomingFlow

        override suspend fun send(action: NodeAction<TestAction>) {
            sentActions.add(action)
        }

        fun simulateIncoming(action: NodeAction<TestAction>) {
            incomingFlow.tryEmit(action)
        }

        fun close() {
            // Complete the flow by not emitting more — tests manage lifecycle via job cancellation
        }
    }

    @Test
    fun handleNodeRegistersAndUnregisters() = runTest {
        val events = mutableListOf<NodeMediatorEvent>()
        val manager = CentralNodeManager<TestAction>(
            scope = this,
            onUpstreamAction = { _, _, _ -> },
            onEvent = { events.add(it) },
        )

        val conn = FakeTypedServerConnection()
        val handleJob = launch {
            manager.handleNode("node-1", conn)
        }
        yield()

        assertTrue(manager.isNodeConnected("node-1"))
        assertEquals(setOf("node-1"), manager.connectedNodeIds())

        handleJob.cancel()
        handleJob.join()

        assertFalse(manager.isNodeConnected("node-1"))
        assertTrue(events.any { it is NodeMediatorEvent.NodeConnected && it.nodeId == "node-1" })
        assertTrue(events.any { it is NodeMediatorEvent.NodeDisconnected && it.nodeId == "node-1" })

        manager.close()
    }

    @Test
    fun upstreamActionsRoutedToCallback() = runTest {
        val received = mutableListOf<Triple<String, String, TestAction>>()
        val manager = CentralNodeManager<TestAction>(
            scope = this,
            onUpstreamAction = { nodeId, roomId, action ->
                received.add(Triple(nodeId, roomId, action))
            },
        )

        val conn = FakeTypedServerConnection()
        val handleJob = launch {
            manager.handleNode("node-1", conn)
        }
        yield()

        conn.simulateIncoming(NodeAction("room-1", TestAction.Message("hello")))
        conn.simulateIncoming(NodeAction("room-2", TestAction.Ping(42)))
        yield()

        assertEquals(2, received.size)
        assertEquals(Triple("node-1", "room-1", TestAction.Message("hello") as TestAction), received[0])
        assertEquals(Triple("node-1", "room-2", TestAction.Ping(42) as TestAction), received[1])

        handleJob.cancel()
        handleJob.join()
        manager.close()
    }

    @Test
    fun sendToRoom() = runTest {
        val registry = InMemoryRoomRegistry()
        val manager = CentralNodeManager<TestAction>(
            roomRegistry = registry,
            scope = this,
            onUpstreamAction = { _, _, _ -> },
        )

        val conn = FakeTypedServerConnection()
        val handleJob = launch {
            manager.handleNode("node-1", conn)
        }
        yield()

        registry.assignRoom("room-1", "node-1")
        manager.sendToRoom("room-1", TestAction.Message("downstream"))

        assertEquals(1, conn.sentActions.size)
        assertEquals(NodeAction<TestAction>("room-1", TestAction.Message("downstream")), conn.sentActions[0])

        handleJob.cancel()
        handleJob.join()
        manager.close()
    }

    @Test
    fun sendToRoomUnknownRoomDoesNothing() = runTest {
        val manager = CentralNodeManager<TestAction>(
            scope = this,
            onUpstreamAction = { _, _, _ -> },
        )

        // No node connected, no room assigned — should silently do nothing
        manager.sendToRoom("unknown-room", TestAction.Message("lost"))

        manager.close()
    }

    @Test
    fun sendToNode() = runTest {
        val manager = CentralNodeManager<TestAction>(
            scope = this,
            onUpstreamAction = { _, _, _ -> },
        )

        val conn = FakeTypedServerConnection()
        val handleJob = launch {
            manager.handleNode("node-1", conn)
        }
        yield()

        manager.sendToNode("node-1", "room-1", TestAction.Ping(99))

        assertEquals(1, conn.sentActions.size)
        assertEquals(NodeAction<TestAction>("room-1", TestAction.Ping(99)), conn.sentActions[0])

        handleJob.cancel()
        handleJob.join()
        manager.close()
    }

    @Test
    fun sendToNodeUnknownNodeDoesNothing() = runTest {
        val manager = CentralNodeManager<TestAction>(
            scope = this,
            onUpstreamAction = { _, _, _ -> },
        )

        // No node connected — should silently do nothing
        manager.sendToNode("unknown-node", "room-1", TestAction.Message("lost"))

        manager.close()
    }

    @Test
    fun broadcastToAllNodes() = runTest {
        val manager = CentralNodeManager<TestAction>(
            scope = this,
            onUpstreamAction = { _, _, _ -> },
        )

        val conn1 = FakeTypedServerConnection()
        val conn2 = FakeTypedServerConnection()
        val handleJob1 = launch { manager.handleNode("node-1", conn1) }
        val handleJob2 = launch { manager.handleNode("node-2", conn2) }
        yield()

        manager.broadcastToAllNodes("room-1", TestAction.Message("broadcast"))

        assertEquals(1, conn1.sentActions.size)
        assertEquals(NodeAction<TestAction>("room-1", TestAction.Message("broadcast")), conn1.sentActions[0])
        assertEquals(1, conn2.sentActions.size)
        assertEquals(NodeAction<TestAction>("room-1", TestAction.Message("broadcast")), conn2.sentActions[0])

        handleJob1.cancel()
        handleJob2.cancel()
        handleJob1.join()
        handleJob2.join()
        manager.close()
    }

    @Test
    fun multipleNodesManaged() = runTest {
        val manager = CentralNodeManager<TestAction>(
            scope = this,
            onUpstreamAction = { _, _, _ -> },
        )

        val conn1 = FakeTypedServerConnection()
        val conn2 = FakeTypedServerConnection()
        val conn3 = FakeTypedServerConnection()
        val handleJob1 = launch { manager.handleNode("node-1", conn1) }
        val handleJob2 = launch { manager.handleNode("node-2", conn2) }
        val handleJob3 = launch { manager.handleNode("node-3", conn3) }
        yield()

        assertEquals(setOf("node-1", "node-2", "node-3"), manager.connectedNodeIds())
        assertTrue(manager.isNodeConnected("node-1"))
        assertTrue(manager.isNodeConnected("node-2"))
        assertTrue(manager.isNodeConnected("node-3"))
        assertFalse(manager.isNodeConnected("node-4"))

        handleJob1.cancel()
        handleJob2.cancel()
        handleJob3.cancel()
        handleJob1.join()
        handleJob2.join()
        handleJob3.join()
        manager.close()
    }

    @Test
    fun nodeDisconnectCleansUpRoomAssignments() = runTest {
        val registry = InMemoryRoomRegistry()
        val events = mutableListOf<NodeMediatorEvent>()
        val manager = CentralNodeManager<TestAction>(
            roomRegistry = registry,
            scope = this,
            onUpstreamAction = { _, _, _ -> },
            onEvent = { events.add(it) },
        )

        val conn = FakeTypedServerConnection()
        val handleJob = launch {
            manager.handleNode("node-1", conn)
        }
        yield()

        registry.assignRoom("room-1", "node-1")
        registry.assignRoom("room-2", "node-1")

        // Disconnect node
        handleJob.cancel()
        handleJob.join()

        // Room assignments should be cleaned up
        assertEquals(null, registry.getNodeForRoom("room-1"))
        assertEquals(null, registry.getNodeForRoom("room-2"))

        manager.close()
    }

    @Test
    fun callbackFailureDoesNotStopRouting() = runTest {
        val events = mutableListOf<NodeMediatorEvent>()
        var callCount = 0
        val manager = CentralNodeManager<TestAction>(
            scope = this,
            onUpstreamAction = { _, _, _ ->
                callCount++
                if (callCount == 1) throw RuntimeException("callback error")
            },
            onEvent = { events.add(it) },
        )

        val conn = FakeTypedServerConnection()
        val handleJob = launch {
            manager.handleNode("node-1", conn)
        }
        yield()

        conn.simulateIncoming(NodeAction("room-1", TestAction.Message("fail")))
        yield()
        conn.simulateIncoming(NodeAction("room-1", TestAction.Message("succeed")))
        yield()

        assertEquals(2, callCount)
        assertTrue(events.any { it is NodeMediatorEvent.CallbackFailed })

        handleJob.cancel()
        handleJob.join()
        manager.close()
    }

    @Test
    fun closeDisconnectsAllNodes() = runTest {
        val events = mutableListOf<NodeMediatorEvent>()
        val manager = CentralNodeManager<TestAction>(
            scope = this,
            onUpstreamAction = { _, _, _ -> },
            onEvent = { events.add(it) },
        )

        val conn1 = FakeTypedServerConnection()
        val conn2 = FakeTypedServerConnection()
        val handleJob1 = launch { manager.handleNode("node-1", conn1) }
        val handleJob2 = launch { manager.handleNode("node-2", conn2) }
        yield()

        assertTrue(manager.isNodeConnected("node-1"))
        assertTrue(manager.isNodeConnected("node-2"))

        manager.close()
        testScheduler.advanceUntilIdle()

        handleJob1.join()
        handleJob2.join()

        assertEquals(emptySet(), manager.connectedNodeIds())
    }

    @Test
    fun broadcastHandlesIndividualSendFailures() = runTest {
        val manager = CentralNodeManager<TestAction>(
            scope = this,
            onUpstreamAction = { _, _, _ -> },
        )

        val failingConn = object : TypedServerConnection<NodeAction<TestAction>> {
            val incomingFlow = MutableSharedFlow<NodeAction<TestAction>>(extraBufferCapacity = 64)
            override val incoming: Flow<NodeAction<TestAction>> = incomingFlow
            override suspend fun send(action: NodeAction<TestAction>) {
                throw RuntimeException("send failed")
            }
        }

        val healthyConn = FakeTypedServerConnection()

        val handleJob1 = launch { manager.handleNode("node-fail", failingConn) }
        val handleJob2 = launch { manager.handleNode("node-ok", healthyConn) }
        yield()

        manager.broadcastToAllNodes("room-1", TestAction.Message("broadcast"))

        // Healthy node should still receive the message despite failing node
        assertEquals(1, healthyConn.sentActions.size)
        assertEquals(
            NodeAction<TestAction>("room-1", TestAction.Message("broadcast")),
            healthyConn.sentActions[0],
        )

        handleJob1.cancel()
        handleJob2.cancel()
        handleJob1.join()
        handleJob2.join()
        manager.close()
    }

    @Test
    fun handleNodeAfterCloseThrows() = runTest {
        val manager = CentralNodeManager<TestAction>(
            scope = this,
            onUpstreamAction = { _, _, _ -> },
        )

        manager.close()

        val conn = FakeTypedServerConnection()
        assertFailsWith<IllegalStateException> {
            manager.handleNode("node-1", conn)
        }
    }

    @Test
    fun duplicateNodeIdReplacesConnection() = runTest {
        val manager = CentralNodeManager<TestAction>(
            scope = this,
            onUpstreamAction = { _, _, _ -> },
        )

        val conn1 = FakeTypedServerConnection()
        val conn2 = FakeTypedServerConnection()

        val handleJob1 = launch { manager.handleNode("node-1", conn1) }
        yield()

        assertTrue(manager.isNodeConnected("node-1"))

        // Same nodeId connects again — replaces the connection
        val handleJob2 = launch { manager.handleNode("node-1", conn2) }
        yield()

        assertTrue(manager.isNodeConnected("node-1"))

        // Send to node-1 should use the new connection
        manager.sendToNode("node-1", "room-1", TestAction.Message("hello"))

        assertEquals(1, conn2.sentActions.size)
        assertEquals(
            NodeAction<TestAction>("room-1", TestAction.Message("hello")),
            conn2.sentActions[0],
        )
        // Old connection should NOT have received the message
        assertTrue(conn1.sentActions.isEmpty())

        handleJob1.cancel()
        handleJob2.cancel()
        handleJob1.join()
        handleJob2.join()
        manager.close()
    }
}
