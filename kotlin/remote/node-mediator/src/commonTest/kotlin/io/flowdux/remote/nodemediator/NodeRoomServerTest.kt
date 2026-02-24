package io.flowdux.remote.nodemediator

import io.flowdux.Action
import io.flowdux.State
import io.flowdux.buildReducer
import io.flowdux.remote.ClientSharedAction
import io.flowdux.remote.nodemediator.transport.NodeTransport
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.pattern.RoomServer
import io.flowdux.remote.server.pattern.SharedStateServer
import io.flowdux.remote.server.pattern.createSharedStateRoomServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NodeRoomServerTest {

    @Serializable
    sealed interface TestAction : Action {
        @Serializable data class Add(val item: String) : TestAction
        @Serializable data class Sync(val items: List<String>) : TestAction, ClientSharedAction
    }

    @Serializable
    data class TestState(val items: List<String> = emptyList()) : State

    private val testReducer = buildReducer<TestState, TestAction> {
        on<TestAction.Add> { state, action ->
            state.copy(items = state.items + action.item)
        }
    }

    private class FakeNodeTransport : NodeTransport<TestAction> {
        val incomingFlow = MutableSharedFlow<NodeAction<TestAction>>(extraBufferCapacity = 64)
        override val incoming: Flow<NodeAction<TestAction>> = incomingFlow

        val sentActions = mutableListOf<NodeAction<TestAction>>()
        val subscribedRooms = mutableSetOf<String>()
        val unsubscribedRooms = mutableListOf<String>()

        var connected = false
            private set

        override suspend fun send(action: NodeAction<TestAction>) { sentActions.add(action) }
        override suspend fun subscribeRoom(roomId: String) { subscribedRooms.add(roomId) }
        override suspend fun unsubscribeRoom(roomId: String) { unsubscribedRooms.add(roomId) }
        override suspend fun connect() { connected = true }
        override suspend fun disconnect() { connected = false }

        fun simulateIncoming(action: NodeAction<TestAction>) {
            incomingFlow.tryEmit(action)
        }
    }

    private class FakeClientConnection : TypedServerConnection<TestAction> {
        override val isActive: Boolean = true
        val incomingFlow = MutableSharedFlow<TestAction>(extraBufferCapacity = 64)
        override val incoming: Flow<TestAction> = incomingFlow

        val sentActions = mutableListOf<TestAction>()

        override suspend fun send(action: TestAction) { sentActions.add(action) }

        fun simulateIncoming(action: TestAction) {
            incomingFlow.tryEmit(action)
        }
    }

    private fun createTestRoomServer(
        scope: kotlinx.coroutines.CoroutineScope,
    ): RoomServer<SharedStateServer<TestState, TestAction>> {
        return createSharedStateRoomServer(
            initialStateFactory = { TestState() },
            reducer = testReducer,
            stateMapper = { state -> TestAction.Sync(state.items) },
            scope = scope,
        )
    }

    @Test
    fun handleClientRegistersRoomWithMediator() = runTest {
        val transport = FakeNodeTransport()
        val roomServer = createTestRoomServer(backgroundScope)
        val server = NodeRoomServer("node-1", transport, roomServer, backgroundScope)
        server.connect()
        testScheduler.advanceTimeBy(1)

        val clientConn = FakeClientConnection()
        val job = backgroundScope.launch {
            server.handleClient("room-1", "session-1", clientConn)
        }
        testScheduler.advanceTimeBy(1)

        assertTrue(server.mediator.hasRoom("room-1"))
        assertTrue(transport.subscribedRooms.contains("room-1"))
        assertTrue(server.roomIds().contains("room-1"))

        job.cancel()
        testScheduler.advanceTimeBy(1)
        server.close()
    }

    @Test
    fun incomingActionForwardedToCentral() = runTest {
        val transport = FakeNodeTransport()
        val roomServer = createTestRoomServer(backgroundScope)
        val server = NodeRoomServer("node-1", transport, roomServer, backgroundScope)
        server.connect()
        testScheduler.advanceTimeBy(1)

        val clientConn = FakeClientConnection()
        val job = backgroundScope.launch {
            server.handleClient("room-1", "session-1", clientConn)
        }
        testScheduler.advanceTimeBy(1)

        // Client sends an action
        clientConn.simulateIncoming(TestAction.Add("hello"))
        testScheduler.advanceTimeBy(1)

        // Verify it was forwarded to Central
        assertEquals(1, transport.sentActions.size)
        assertEquals(
            NodeAction<TestAction>("room-1", TestAction.Add("hello")),
            transport.sentActions[0],
        )

        job.cancel()
        testScheduler.advanceTimeBy(1)
        server.close()
    }

    @Test
    fun centralActionDispatchedToRoomStore() = runTest {
        val transport = FakeNodeTransport()
        val roomServer = createTestRoomServer(backgroundScope)
        val server = NodeRoomServer("node-1", transport, roomServer, backgroundScope)
        server.connect()
        testScheduler.advanceTimeBy(1)

        val clientConn = FakeClientConnection()
        val job = backgroundScope.launch {
            server.handleClient("room-1", "session-1", clientConn)
        }
        testScheduler.advanceTimeBy(1)

        // Central sends an action
        transport.simulateIncoming(NodeAction<TestAction>("room-1", TestAction.Add("from-central")))
        testScheduler.advanceTimeBy(1)

        // Verify it was dispatched to the room store
        val room = roomServer.getRoom("room-1")!!
        assertTrue(room.currentState.items.contains("from-central"))

        job.cancel()
        testScheduler.advanceTimeBy(1)
        server.close()
    }

    @Test
    fun forwardingFailureDoesNotDisconnectClient() = runTest {
        // Use a transport that fails on send
        val transport = object : NodeTransport<TestAction> {
            val incomingFlow = MutableSharedFlow<NodeAction<TestAction>>(extraBufferCapacity = 64)
            override val incoming: Flow<NodeAction<TestAction>> = incomingFlow
            override suspend fun send(action: NodeAction<TestAction>) {
                throw RuntimeException("Central unreachable")
            }
            override suspend fun subscribeRoom(roomId: String) {}
            override suspend fun unsubscribeRoom(roomId: String) {}
            override suspend fun connect() {}
            override suspend fun disconnect() {}
        }

        val roomServer = createTestRoomServer(backgroundScope)
        val events = mutableListOf<NodeMediatorEvent>()
        val server = NodeRoomServer("node-1", transport, roomServer, backgroundScope, onEvent = { events.add(it) })
        server.connect()
        testScheduler.advanceTimeBy(1)

        val clientConn = FakeClientConnection()
        val job = backgroundScope.launch {
            server.handleClient("room-1", "session-1", clientConn)
        }
        testScheduler.advanceTimeBy(1)

        // Client sends an action — forwarding will fail
        clientConn.simulateIncoming(TestAction.Add("hello"))
        testScheduler.advanceTimeBy(1)

        // Verify the action was still dispatched to the room store despite forwarding failure
        val room = roomServer.getRoom("room-1")!!
        assertTrue(room.currentState.items.contains("hello"))

        // Client should still be connected (handleClient didn't throw)
        assertTrue(job.isActive)

        job.cancel()
        testScheduler.advanceTimeBy(1)
        server.close()
    }

    @Test
    fun destroyRoomIfEmptyUnregistersFromMediator() = runTest {
        val transport = FakeNodeTransport()
        val roomServer = createTestRoomServer(backgroundScope)
        val server = NodeRoomServer("node-1", transport, roomServer, backgroundScope)
        server.connect()
        testScheduler.advanceTimeBy(1)

        val clientConn = FakeClientConnection()
        val job = backgroundScope.launch {
            server.handleClient("room-1", "session-1", clientConn)
        }
        testScheduler.advanceTimeBy(1)

        assertTrue(server.mediator.hasRoom("room-1"))

        // Disconnect client
        job.cancel()
        testScheduler.advanceTimeBy(1)

        // Destroy empty room
        val destroyed = server.destroyRoomIfEmpty("room-1")
        assertTrue(destroyed)
        assertFalse(server.mediator.hasRoom("room-1"))
        assertTrue(transport.unsubscribedRooms.contains("room-1"))

        server.close()
    }

    @Test
    fun onUnknownRoomAutoCreatesRoom() = runTest {
        val transport = FakeNodeTransport()
        val roomServer = createTestRoomServer(backgroundScope)
        val server = NodeRoomServer("node-1", transport, roomServer, backgroundScope)
        server.connect()
        testScheduler.advanceTimeBy(1)

        // Central sends an action for a room that doesn't exist yet
        transport.simulateIncoming(NodeAction<TestAction>("auto-room", TestAction.Add("auto-created")))
        testScheduler.advanceTimeBy(1)

        // Room should have been auto-created
        assertTrue(server.mediator.hasRoom("auto-room"))
        val room = roomServer.getRoom("auto-room")!!
        assertTrue(room.currentState.items.contains("auto-created"))

        server.close()
    }

    @Test
    fun multipleClientsInSameRoom() = runTest {
        val transport = FakeNodeTransport()
        val roomServer = createTestRoomServer(backgroundScope)
        val server = NodeRoomServer("node-1", transport, roomServer, backgroundScope)
        server.connect()
        testScheduler.advanceTimeBy(1)

        val client1 = FakeClientConnection()
        val client2 = FakeClientConnection()

        val job1 = backgroundScope.launch { server.handleClient("room-1", "session-1", client1) }
        val job2 = backgroundScope.launch { server.handleClient("room-1", "session-2", client2) }
        testScheduler.advanceTimeBy(1)

        // Both clients are in the same room
        val room = roomServer.getRoom("room-1")!!
        assertEquals(2, room.sessionCount())

        // Client 1 sends an action — forwarded to Central
        client1.simulateIncoming(TestAction.Add("from-client-1"))
        testScheduler.advanceTimeBy(1)

        assertEquals(1, transport.sentActions.size)
        assertTrue(room.currentState.items.contains("from-client-1"))

        job1.cancel()
        job2.cancel()
        testScheduler.advanceTimeBy(1)
        server.close()
    }

    @Test
    fun dispatchAndForwardUpdatesStoreAndSendsToCentral() = runTest {
        val transport = FakeNodeTransport()
        val roomServer = createTestRoomServer(backgroundScope)
        val server = NodeRoomServer("node-1", transport, roomServer, backgroundScope)
        server.connect()
        testScheduler.advanceTimeBy(1)

        // Create room first via handleClient
        val clientConn = FakeClientConnection()
        val job = backgroundScope.launch { server.handleClient("room-1", "session-1", clientConn) }
        testScheduler.advanceTimeBy(1)

        // Use dispatchAndForward
        server.dispatchAndForward("room-1", TestAction.Add("dispatched"))
        testScheduler.advanceTimeBy(1)

        // Verify store was updated
        val room = roomServer.getRoom("room-1")!!
        assertTrue(room.currentState.items.contains("dispatched"))

        // Verify it was forwarded
        assertTrue(transport.sentActions.any {
            it == NodeAction<TestAction>("room-1", TestAction.Add("dispatched"))
        })

        job.cancel()
        testScheduler.advanceTimeBy(1)
        server.close()
    }

    @Test
    fun cleanupEmptyRoomsUnregistersFromMediator() = runTest {
        val transport = FakeNodeTransport()
        val roomServer = createTestRoomServer(backgroundScope)
        val server = NodeRoomServer("node-1", transport, roomServer, backgroundScope)
        server.connect()
        testScheduler.advanceTimeBy(1)

        // Create two rooms
        val client1 = FakeClientConnection()
        val client2 = FakeClientConnection()
        val job1 = backgroundScope.launch { server.handleClient("room-1", "session-1", client1) }
        val job2 = backgroundScope.launch { server.handleClient("room-2", "session-2", client2) }
        testScheduler.advanceTimeBy(1)

        assertEquals(2, server.roomIds().size)

        // Disconnect client 1 only
        job1.cancel()
        testScheduler.advanceTimeBy(1)

        // Cleanup empty rooms
        val destroyed = server.cleanupEmptyRooms()
        assertEquals(listOf("room-1"), destroyed)
        assertFalse(server.mediator.hasRoom("room-1"))
        assertTrue(server.mediator.hasRoom("room-2"))

        job2.cancel()
        testScheduler.advanceTimeBy(1)
        server.close()
    }

    @Test
    fun dispatchAndForwardNoOpForNonexistentRoom() = runTest {
        val transport = FakeNodeTransport()
        val roomServer = createTestRoomServer(backgroundScope)
        val server = NodeRoomServer("node-1", transport, roomServer, backgroundScope)
        server.connect()
        testScheduler.advanceTimeBy(1)

        // Should not throw for nonexistent room
        server.dispatchAndForward("nonexistent", TestAction.Add("nothing"))
        testScheduler.advanceTimeBy(1)

        assertEquals(0, transport.sentActions.size)

        server.close()
    }
}
