package io.flowdux.remote.nodemediator

import io.flowdux.Action
import io.flowdux.remote.ConnectionState
import io.flowdux.remote.TypedClientConnection
import io.flowdux.remote.server.connection.TypedServerConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end integration tests connecting NodeMediator ↔ CentralNodeManager
 * via linked fake connections.
 */
class IntegrationTest {

    @Serializable
    sealed interface TestAction : Action {
        @Serializable data class Message(val text: String) : TestAction
        @Serializable data class Broadcast(val text: String) : TestAction
    }

    /**
     * A linked pair of fake connections that simulate a bidirectional transport.
     *
     * Messages sent via the client connection arrive in the server connection's incoming flow,
     * and vice versa.
     */
    private class LinkedConnection {
        // client → server channel
        private val clientToServer = MutableSharedFlow<NodeAction<TestAction>>(extraBufferCapacity = 64)

        // server → client channel
        private val serverToClient = MutableSharedFlow<NodeAction<TestAction>>(extraBufferCapacity = 64)

        val clientSide = object : TypedClientConnection<NodeAction<TestAction>> {
            private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
            override val connectionState: StateFlow<ConnectionState> = _connectionState
            override val incoming: Flow<NodeAction<TestAction>> = serverToClient

            override suspend fun send(action: NodeAction<TestAction>) {
                clientToServer.emit(action)
            }

            override suspend fun connect() {
                _connectionState.value = ConnectionState.CONNECTED
            }

            override suspend fun disconnect() {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }

        val serverSide = object : TypedServerConnection<NodeAction<TestAction>> {
            override val incoming: Flow<NodeAction<TestAction>> = clientToServer

            override suspend fun send(action: NodeAction<TestAction>) {
                serverToClient.emit(action)
            }
        }
    }

    @Test
    fun centralToNodeDownstreamRouting() = runTest {
        val registry = InMemoryRoomRegistry()
        val link = LinkedConnection()
        val receivedActions = mutableListOf<TestAction>()

        val manager = CentralNodeManager<TestAction>(
            roomRegistry = registry,
            scope = this,
            onUpstreamAction = { _, _, _ -> },
        )

        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            centralConnection = link.clientSide,
            scope = this,
        )

        // Start node handling on central
        val handleJob = launch { manager.handleNode("node-1", link.serverSide) }
        yield()

        // Start mediator on node
        mediator.connect()
        testScheduler.advanceUntilIdle()

        // Register room on mediator
        mediator.registerRoom("room-1") { receivedActions.add(it) }
        registry.assignRoom("room-1", "node-1")

        // Central sends to room
        manager.sendToRoom("room-1", TestAction.Message("hello from central"))
        yield()
        testScheduler.advanceUntilIdle()

        assertEquals(1, receivedActions.size)
        assertEquals(TestAction.Message("hello from central"), receivedActions[0])

        mediator.close()
        handleJob.cancel()
        handleJob.join()
        manager.close()
    }

    @Test
    fun nodeToentralUpstreamRouting() = runTest {
        val link = LinkedConnection()
        val upstreamReceived = mutableListOf<Triple<String, String, TestAction>>()

        val manager = CentralNodeManager<TestAction>(
            scope = this,
            onUpstreamAction = { nodeId, roomId, action ->
                upstreamReceived.add(Triple(nodeId, roomId, action))
            },
        )

        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            centralConnection = link.clientSide,
            scope = this,
        )

        val handleJob = launch { manager.handleNode("node-1", link.serverSide) }
        yield()

        mediator.connect()
        testScheduler.advanceUntilIdle()

        // Node forwards action to central
        mediator.forwardToCentral("room-1", TestAction.Message("from node"))
        yield()
        testScheduler.advanceUntilIdle()

        assertEquals(1, upstreamReceived.size)
        assertEquals(
            Triple("node-1", "room-1", TestAction.Message("from node") as TestAction),
            upstreamReceived[0],
        )

        mediator.close()
        handleJob.cancel()
        handleJob.join()
        manager.close()
    }

    @Test
    fun crossNodeRouting() = runTest {
        val registry = InMemoryRoomRegistry()
        val linkA = LinkedConnection()
        val linkB = LinkedConnection()

        val nodeAActions = mutableListOf<TestAction>()
        val nodeBActions = mutableListOf<TestAction>()

        lateinit var manager: CentralNodeManager<TestAction>
        manager = CentralNodeManager<TestAction>(
            roomRegistry = registry,
            scope = this,
            onUpstreamAction = { _, roomId, action ->
                // Central relays upstream action to the target room's node
                manager.sendToRoom(roomId, action)
            },
        )

        val mediatorA = NodeMediator<TestAction>(
            nodeId = "node-A",
            centralConnection = linkA.clientSide,
            scope = this,
        )
        val mediatorB = NodeMediator<TestAction>(
            nodeId = "node-B",
            centralConnection = linkB.clientSide,
            scope = this,
        )

        val handleJobA = launch { manager.handleNode("node-A", linkA.serverSide) }
        val handleJobB = launch { manager.handleNode("node-B", linkB.serverSide) }
        yield()

        mediatorA.connect()
        mediatorB.connect()
        testScheduler.advanceUntilIdle()

        mediatorA.registerRoom("room-A") { nodeAActions.add(it) }
        mediatorB.registerRoom("room-B") { nodeBActions.add(it) }
        registry.assignRoom("room-A", "node-A")
        registry.assignRoom("room-B", "node-B")

        // Node A sends to room-B (which lives on Node B)
        mediatorA.forwardToCentral("room-B", TestAction.Message("cross-node hello"))
        yield()
        testScheduler.advanceUntilIdle()

        assertEquals(1, nodeBActions.size)
        assertEquals(TestAction.Message("cross-node hello"), nodeBActions[0])
        assertTrue(nodeAActions.isEmpty(), "Node A should not receive the action")

        mediatorA.close()
        mediatorB.close()
        handleJobA.cancel()
        handleJobB.cancel()
        handleJobA.join()
        handleJobB.join()
        manager.close()
    }

    @Test
    fun nodeDisconnectAndReconnect() = runTest {
        val registry = InMemoryRoomRegistry()
        val events = mutableListOf<NodeMediatorEvent>()

        val manager = CentralNodeManager<TestAction>(
            roomRegistry = registry,
            scope = this,
            onUpstreamAction = { _, _, _ -> },
            onEvent = { events.add(it) },
        )

        // First connection
        val link1 = LinkedConnection()
        val handleJob1 = launch { manager.handleNode("node-1", link1.serverSide) }
        yield()

        registry.assignRoom("room-1", "node-1")
        assertTrue(manager.isNodeConnected("node-1"))

        // Disconnect
        handleJob1.cancel()
        handleJob1.join()
        assertFalse(manager.isNodeConnected("node-1"))

        // Room assignments should be cleaned up
        assertEquals(null, registry.getNodeForRoom("room-1"))

        // Reconnect
        val link2 = LinkedConnection()
        val receivedActions = mutableListOf<TestAction>()

        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            centralConnection = link2.clientSide,
            scope = this,
        )

        val handleJob2 = launch { manager.handleNode("node-1", link2.serverSide) }
        yield()

        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.registerRoom("room-1") { receivedActions.add(it) }
        registry.assignRoom("room-1", "node-1")

        assertTrue(manager.isNodeConnected("node-1"))

        // Send after reconnect
        manager.sendToRoom("room-1", TestAction.Message("after reconnect"))
        yield()
        testScheduler.advanceUntilIdle()

        assertEquals(1, receivedActions.size)
        assertEquals(TestAction.Message("after reconnect"), receivedActions[0])

        mediator.close()
        handleJob2.cancel()
        handleJob2.join()
        manager.close()
    }

    @Test
    fun roomRegistryBasedRoutingAccuracy() = runTest {
        val registry = InMemoryRoomRegistry()
        val linkA = LinkedConnection()
        val linkB = LinkedConnection()

        val nodeARoom1 = mutableListOf<TestAction>()
        val nodeARoom2 = mutableListOf<TestAction>()
        val nodeBRoom3 = mutableListOf<TestAction>()

        val manager = CentralNodeManager<TestAction>(
            roomRegistry = registry,
            scope = this,
            onUpstreamAction = { _, _, _ -> },
        )

        val mediatorA = NodeMediator<TestAction>(
            nodeId = "node-A",
            centralConnection = linkA.clientSide,
            scope = this,
        )
        val mediatorB = NodeMediator<TestAction>(
            nodeId = "node-B",
            centralConnection = linkB.clientSide,
            scope = this,
        )

        val handleJobA = launch { manager.handleNode("node-A", linkA.serverSide) }
        val handleJobB = launch { manager.handleNode("node-B", linkB.serverSide) }
        yield()

        mediatorA.connect()
        mediatorB.connect()
        testScheduler.advanceUntilIdle()

        mediatorA.registerRoom("room-1") { nodeARoom1.add(it) }
        mediatorA.registerRoom("room-2") { nodeARoom2.add(it) }
        mediatorB.registerRoom("room-3") { nodeBRoom3.add(it) }

        registry.assignRoom("room-1", "node-A")
        registry.assignRoom("room-2", "node-A")
        registry.assignRoom("room-3", "node-B")

        // Send to each room
        manager.sendToRoom("room-1", TestAction.Message("to room-1"))
        manager.sendToRoom("room-2", TestAction.Message("to room-2"))
        manager.sendToRoom("room-3", TestAction.Message("to room-3"))
        yield()
        testScheduler.advanceUntilIdle()

        val expectedRoom1: List<TestAction> = listOf(TestAction.Message("to room-1"))
        val expectedRoom2: List<TestAction> = listOf(TestAction.Message("to room-2"))
        val expectedRoom3: List<TestAction> = listOf(TestAction.Message("to room-3"))
        assertEquals(expectedRoom1, nodeARoom1)
        assertEquals(expectedRoom2, nodeARoom2)
        assertEquals(expectedRoom3, nodeBRoom3)

        mediatorA.close()
        mediatorB.close()
        handleJobA.cancel()
        handleJobB.cancel()
        handleJobA.join()
        handleJobB.join()
        manager.close()
    }

    @Test
    fun bidirectionalRapidExchange() = runTest {
        val registry = InMemoryRoomRegistry()
        val link = LinkedConnection()
        val nodeReceived = mutableListOf<TestAction>()
        val centralReceived = mutableListOf<Triple<String, String, TestAction>>()

        val manager = CentralNodeManager<TestAction>(
            roomRegistry = registry,
            scope = this,
            onUpstreamAction = { nodeId, roomId, action ->
                centralReceived.add(Triple(nodeId, roomId, action))
            },
        )

        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            centralConnection = link.clientSide,
            scope = this,
        )

        val handleJob = launch { manager.handleNode("node-1", link.serverSide) }
        yield()

        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.registerRoom("room-1") { nodeReceived.add(it) }
        registry.assignRoom("room-1", "node-1")

        // Simultaneously send 10 messages each way
        val centralSending = async {
            repeat(10) { i ->
                manager.sendToRoom("room-1", TestAction.Message("central-$i"))
            }
        }
        val nodeSending = async {
            repeat(10) { i ->
                mediator.forwardToCentral("room-1", TestAction.Message("node-$i"))
            }
        }
        awaitAll(centralSending, nodeSending)
        yield()
        testScheduler.advanceUntilIdle()

        // All 10 central→node messages should arrive
        assertEquals(10, nodeReceived.size)
        val expectedNodeMessages = (0 until 10).map { TestAction.Message("central-$it") as TestAction }
        assertEquals(expectedNodeMessages, nodeReceived)

        // All 10 node→central messages should arrive
        assertEquals(10, centralReceived.size)
        val expectedCentralMessages = (0 until 10).map {
            Triple("node-1", "room-1", TestAction.Message("node-$it") as TestAction)
        }
        assertEquals(expectedCentralMessages, centralReceived)

        mediator.close()
        handleJob.cancel()
        handleJob.join()
        manager.close()
    }
}
