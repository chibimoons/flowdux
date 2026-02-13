package io.flowdux.remote.nodemediator

import io.flowdux.Action
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeMediatorTest {

    @Serializable
    sealed interface TestAction : Action {
        @Serializable data class Message(val text: String) : TestAction
        @Serializable data class Ping(val id: Int) : TestAction
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

    @Test
    fun registerAndUnregisterRoom() = runTest {
        val connection = FakeNodeTransport()
        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.registerRoom("room-1") { }
        assertTrue(mediator.hasRoom("room-1"))
        assertEquals(setOf("room-1"), mediator.roomIds())

        mediator.unregisterRoom("room-1")
        assertFalse(mediator.hasRoom("room-1"))
        assertEquals(emptySet(), mediator.roomIds())

        mediator.close()
    }

    @Test
    fun multipleRooms() = runTest {
        val connection = FakeNodeTransport()
        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.registerRoom("room-1") { }
        mediator.registerRoom("room-2") { }
        mediator.registerRoom("room-3") { }

        assertEquals(setOf("room-1", "room-2", "room-3"), mediator.roomIds())

        mediator.close()
    }

    @Test
    fun downstreamRoutingToCorrectRoom() = runTest {
        val connection = FakeNodeTransport()
        val room1Actions = mutableListOf<TestAction>()
        val room2Actions = mutableListOf<TestAction>()

        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.registerRoom("room-1") { room1Actions.add(it) }
        mediator.registerRoom("room-2") { room2Actions.add(it) }

        connection.simulateIncoming(NodeAction("room-1", TestAction.Message("hello")))
        connection.simulateIncoming(NodeAction("room-2", TestAction.Ping(42)))
        connection.simulateIncoming(NodeAction("room-1", TestAction.Message("world")))
        yield()

        val expectedRoom1: List<TestAction> = listOf(
            TestAction.Message("hello"),
            TestAction.Message("world"),
        )
        assertEquals(expectedRoom1, room1Actions)
        val expectedRoom2: List<TestAction> = listOf(TestAction.Ping(42))
        assertEquals(expectedRoom2, room2Actions)

        mediator.close()
    }

    @Test
    fun upstreamForwardToCentral() = runTest {
        val connection = FakeNodeTransport()
        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.registerRoom("room-1") { }

        mediator.forwardToCentral("room-1", TestAction.Message("upstream"))
        mediator.forwardToCentral("room-2", TestAction.Ping(99))

        assertEquals(2, connection.sentActions.size)
        assertEquals(NodeAction<TestAction>("room-1", TestAction.Message("upstream")), connection.sentActions[0])
        assertEquals(NodeAction<TestAction>("room-2", TestAction.Ping(99)), connection.sentActions[1])

        mediator.close()
    }

    @Test
    fun unknownRoomSilentDrop() = runTest {
        val connection = FakeNodeTransport()
        val room1Actions = mutableListOf<TestAction>()

        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.registerRoom("room-1") { room1Actions.add(it) }

        // Action for unknown room — should be silently dropped
        connection.simulateIncoming(NodeAction("unknown-room", TestAction.Message("lost")))
        connection.simulateIncoming(NodeAction("room-1", TestAction.Message("received")))
        yield()

        val expected: List<TestAction> = listOf(TestAction.Message("received"))
        assertEquals(expected, room1Actions)

        mediator.close()
    }

    @Test
    fun unknownRoomCallbackInvoked() = runTest {
        val connection = FakeNodeTransport()
        val unknownActions = mutableListOf<Pair<String, TestAction>>()

        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
            onUnknownRoom = { roomId, action -> unknownActions.add(roomId to action) },
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        connection.simulateIncoming(NodeAction("unknown", TestAction.Message("hello")))
        yield()

        assertEquals(1, unknownActions.size)
        assertEquals("unknown" to TestAction.Message("hello"), unknownActions[0])

        mediator.close()
    }

    @Test
    fun connectDisconnectLifecycle() = runTest {
        val connection = FakeNodeTransport()
        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )

        assertFalse(connection.connected)

        mediator.connect()
        testScheduler.advanceUntilIdle()
        assertTrue(connection.connected)

        mediator.disconnect()
        testScheduler.advanceUntilIdle()
        assertFalse(connection.connected)

        // Rooms should be preserved after disconnect
        mediator.registerRoom("room-1") { }
        assertTrue(mediator.hasRoom("room-1"))

        // Can reconnect
        mediator.connect()
        testScheduler.advanceUntilIdle()
        assertTrue(connection.connected)

        mediator.close()
    }

    @Test
    fun closeThrowsOnSubsequentUse() = runTest {
        val connection = FakeNodeTransport()
        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.close()

        assertFailsWith<IllegalStateException> {
            mediator.registerRoom("room-1") { }
        }
        assertFailsWith<IllegalStateException> {
            mediator.connect()
        }
        assertFailsWith<IllegalStateException> {
            mediator.forwardToCentral("room-1", TestAction.Message("fail"))
        }
    }

    @Test
    fun closeClearsRooms() = runTest {
        val connection = FakeNodeTransport()
        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.registerRoom("room-1") { }
        mediator.registerRoom("room-2") { }

        mediator.close()

        assertEquals(emptySet(), mediator.roomIds())
    }

    @Test
    fun concurrentRegisterRooms() = runTest {
        val connection = FakeNodeTransport()
        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        val deferreds = (1..50).map { i ->
            async {
                mediator.registerRoom("room-$i") { }
            }
        }
        deferreds.awaitAll()

        assertEquals(50, mediator.roomIds().size)

        mediator.close()
    }

    @Test
    fun rapidRegisterUnregisterRegister() = runTest {
        val connection = FakeNodeTransport()
        val actions = mutableListOf<TestAction>()
        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.registerRoom("room-1") { actions.add(it) }
        mediator.unregisterRoom("room-1")
        mediator.registerRoom("room-1") { actions.add(it) }

        connection.simulateIncoming(NodeAction("room-1", TestAction.Message("after re-register")))
        yield()

        val expected: List<TestAction> = listOf(TestAction.Message("after re-register"))
        assertEquals(expected, actions)

        mediator.close()
    }

    @Test
    fun connectIsIdempotent() = runTest {
        val connection = FakeNodeTransport()
        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )

        mediator.connect()
        mediator.connect() // Should not throw or create duplicate jobs
        testScheduler.advanceUntilIdle()

        assertTrue(connection.connected)

        mediator.close()
    }

    @Test
    fun callbackFailureDoesNotStopRouting() = runTest {
        val connection = FakeNodeTransport()
        val events = mutableListOf<NodeMediatorEvent>()
        val room2Actions = mutableListOf<TestAction>()

        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
            onEvent = { events.add(it) },
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.registerRoom("room-1") { throw RuntimeException("handler error") }
        mediator.registerRoom("room-2") { room2Actions.add(it) }

        connection.simulateIncoming(NodeAction("room-1", TestAction.Message("fail")))
        yield()
        connection.simulateIncoming(NodeAction("room-2", TestAction.Message("success")))
        yield()

        // Routing should continue despite the handler failure
        val expectedRoom2: List<TestAction> = listOf(TestAction.Message("success"))
        assertEquals(expectedRoom2, room2Actions)
        assertTrue(events.any { it is NodeMediatorEvent.CallbackFailed })

        mediator.close()
    }

    @Test
    fun onUnknownRoomCallbackExceptionDoesNotStopRouting() = runTest {
        val connection = FakeNodeTransport()
        val events = mutableListOf<NodeMediatorEvent>()
        val room1Actions = mutableListOf<TestAction>()

        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
            onUnknownRoom = { _, _ -> throw RuntimeException("unknown room callback error") },
            onEvent = { events.add(it) },
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.registerRoom("room-1") { room1Actions.add(it) }

        // Action for unknown room — onUnknownRoom throws
        connection.simulateIncoming(NodeAction("unknown", TestAction.Message("boom")))
        yield()
        // Action for known room — should still be routed
        connection.simulateIncoming(NodeAction("room-1", TestAction.Message("ok")))
        yield()

        val expected: List<TestAction> = listOf(TestAction.Message("ok"))
        assertEquals(expected, room1Actions)
        assertTrue(events.any { it is NodeMediatorEvent.CallbackFailed && it.roomId == "unknown" })

        mediator.close()
    }

    @Test
    fun onEventCallbackExceptionDoesNotBreakRouting() = runTest {
        val connection = FakeNodeTransport()
        val room1Actions = mutableListOf<TestAction>()

        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
            onEvent = { throw RuntimeException("event callback error") },
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        // Register a room that throws to trigger onEvent via CallbackFailed
        mediator.registerRoom("room-bad") { throw RuntimeException("handler error") }
        mediator.registerRoom("room-1") { room1Actions.add(it) }

        // Trigger CallbackFailed → onEvent throws → routing should still continue
        connection.simulateIncoming(NodeAction("room-bad", TestAction.Message("fail")))
        yield()
        connection.simulateIncoming(NodeAction("room-1", TestAction.Message("success")))
        yield()

        val expected: List<TestAction> = listOf(TestAction.Message("success"))
        assertEquals(expected, room1Actions)

        mediator.close()
    }

    @Test
    fun transportErrorTriggersRoutingStoppedEvent() = runTest {
        val errorSignal = CompletableDeferred<Unit>()

        val errorTransport = object : NodeTransport<TestAction> {
            override val incoming: Flow<NodeAction<TestAction>> = flow {
                errorSignal.await()
                throw RuntimeException("transport error")
            }

            override suspend fun send(action: NodeAction<TestAction>) {}
            override suspend fun subscribeRoom(roomId: String) {}
            override suspend fun unsubscribeRoom(roomId: String) {}
            override suspend fun connect() {}
            override suspend fun disconnect() {}
        }

        val events = mutableListOf<NodeMediatorEvent>()
        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = errorTransport,
            scope = this,
            onEvent = { events.add(it) },
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        // Trigger the transport error
        errorSignal.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertTrue(events.any { it is NodeMediatorEvent.RoutingStopped })

        mediator.close()
    }

    @Test
    fun roomHandlersPreservedAfterReconnect() = runTest {
        val connection = FakeNodeTransport()
        val room1Actions = mutableListOf<TestAction>()

        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.registerRoom("room-1") { room1Actions.add(it) }

        // Disconnect and reconnect
        mediator.disconnect()
        testScheduler.advanceUntilIdle()

        assertTrue(mediator.hasRoom("room-1"), "Room should be preserved after disconnect")

        mediator.connect()
        testScheduler.advanceUntilIdle()

        // Send action after reconnect — handler should still work
        connection.simulateIncoming(NodeAction("room-1", TestAction.Message("after reconnect")))
        yield()

        val expected: List<TestAction> = listOf(TestAction.Message("after reconnect"))
        assertEquals(expected, room1Actions)

        mediator.close()
    }

    @Test
    fun unregisterNonexistentRoomDoesNothing() = runTest {
        val connection = FakeNodeTransport()
        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        // Should not throw
        mediator.unregisterRoom("nonexistent-room")
        assertEquals(emptySet(), mediator.roomIds())

        mediator.close()
    }

    @Test
    fun registerRoomCallsTransportSubscribe() = runTest {
        val connection = FakeNodeTransport()
        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.registerRoom("room-1") { }
        mediator.registerRoom("room-2") { }

        assertEquals(setOf("room-1", "room-2"), connection.subscribedRooms)

        mediator.close()
    }

    @Test
    fun unregisterRoomCallsTransportUnsubscribe() = runTest {
        val connection = FakeNodeTransport()
        val mediator = NodeMediator<TestAction>(
            nodeId = "node-1",
            transport = connection,
            scope = this,
        )
        mediator.connect()
        testScheduler.advanceUntilIdle()

        mediator.registerRoom("room-1") { }
        mediator.registerRoom("room-2") { }
        mediator.unregisterRoom("room-1")

        assertEquals(listOf("room-1"), connection.unsubscribedRooms)

        mediator.close()
    }
}
