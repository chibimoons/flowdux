package io.flowdux.remote.multiplexer

import io.flowdux.Action
import io.flowdux.remote.ConnectionState
import io.flowdux.remote.TypedClientConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClientConnectionMultiplexerTest {

    @Serializable
    sealed interface TestAction : Action {
        @Serializable data class Message(val text: String) : TestAction
        @Serializable data class Ping(val id: Int) : TestAction
    }

    private fun routed(roomId: String, action: TestAction): RoutedAction<TestAction> =
        RoutedAction(roomId, action)

    private class FakeTypedClientConnection<A : Action> : TypedClientConnection<RoutedAction<A>> {
        private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        override val connectionState: StateFlow<ConnectionState> = _connectionState

        val incomingFlow = MutableSharedFlow<RoutedAction<A>>(extraBufferCapacity = 64)
        override val incoming: Flow<RoutedAction<A>> = incomingFlow

        val sentActions = mutableListOf<RoutedAction<A>>()

        override suspend fun send(action: RoutedAction<A>) {
            sentActions.add(action)
        }

        override suspend fun connect() {
            _connectionState.value = ConnectionState.CONNECTED
        }

        override suspend fun disconnect() {
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        fun simulateIncoming(action: RoutedAction<A>) {
            incomingFlow.tryEmit(action)
        }
    }

    @Test
    fun connectEstablishesPhysicalConnection() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)

        assertEquals(ConnectionState.DISCONNECTED, mux.connectionState.value)
        mux.connect()
        // connect() launches a coroutine - advance scheduler to execute it
        testScheduler.advanceUntilIdle()
        assertEquals(ConnectionState.CONNECTED, mux.connectionState.value)

        mux.close()
    }

    @Test
    fun getOrCreateRoomCreatesNewRoom() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)
        mux.connect()

        val room1 = mux.getOrCreateRoom("room-1")
        assertTrue(mux.hasRoom("room-1"))
        assertEquals(setOf("room-1"), mux.roomIds())

        mux.close()
    }

    @Test
    fun getOrCreateRoomReturnsSameInstance() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)
        mux.connect()

        val room1a = mux.getOrCreateRoom("room-1")
        val room1b = mux.getOrCreateRoom("room-1")
        assertTrue(room1a === room1b)

        mux.close()
    }

    @Test
    fun multipleRoomsCanBeCreated() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)
        mux.connect()

        mux.getOrCreateRoom("room-1")
        mux.getOrCreateRoom("room-2")
        mux.getOrCreateRoom("room-3")

        assertEquals(setOf("room-1", "room-2", "room-3"), mux.roomIds())

        mux.close()
    }

    @Test
    fun removeRoomRemovesRoom() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)
        mux.connect()

        mux.getOrCreateRoom("room-1")
        assertTrue(mux.hasRoom("room-1"))

        mux.removeRoom("room-1")
        assertFalse(mux.hasRoom("room-1"))
        assertEquals(emptySet(), mux.roomIds())

        mux.close()
    }

    @Test
    fun incomingActionsRoutedToCorrectRoom() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)
        mux.connect()

        val room1 = mux.getOrCreateRoom("room-1")
        val room2 = mux.getOrCreateRoom("room-2")

        val room1Actions = mutableListOf<TestAction>()
        val room2Actions = mutableListOf<TestAction>()

        val job1 = launch {
            room1.incoming.take(2).toList(room1Actions)
        }
        val job2 = launch {
            room2.incoming.take(1).toList(room2Actions)
        }

        // Allow collectors to start
        yield()

        physical.simulateIncoming(routed("room-1", TestAction.Message("hello from room 1")))
        physical.simulateIncoming(routed("room-2", TestAction.Message("hello from room 2")))
        physical.simulateIncoming(routed("room-1", TestAction.Ping(42)))

        withTimeout(1000) {
            job1.join()
            job2.join()
        }

        val expectedRoom1: List<TestAction> = listOf(
            TestAction.Message("hello from room 1"),
            TestAction.Ping(42),
        )
        assertEquals(expectedRoom1, room1Actions)

        val expectedRoom2: List<TestAction> = listOf(
            TestAction.Message("hello from room 2"),
        )
        assertEquals(expectedRoom2, room2Actions)

        mux.close()
    }

    @Test
    fun outgoingActionsTaggedWithRoomId() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)
        mux.connect()

        val room1 = mux.getOrCreateRoom("room-1")
        val room2 = mux.getOrCreateRoom("room-2")

        room1.send(TestAction.Message("from room 1"))
        room2.send(TestAction.Ping(99))
        room1.send(TestAction.Message("another from room 1"))

        assertEquals(3, physical.sentActions.size)
        assertEquals(routed("room-1", TestAction.Message("from room 1")), physical.sentActions[0])
        assertEquals(routed("room-2", TestAction.Ping(99)), physical.sentActions[1])
        assertEquals(routed("room-1", TestAction.Message("another from room 1")), physical.sentActions[2])

        mux.close()
    }

    @Test
    fun unknownRoomMessagesSilentlyDropped() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)
        mux.connect()

        val room1 = mux.getOrCreateRoom("room-1")
        val room1Actions = mutableListOf<TestAction>()

        val job = launch {
            room1.incoming.take(1).toList(room1Actions)
        }

        // Allow collector to start
        yield()

        // Message to unknown room should be silently dropped
        physical.simulateIncoming(routed("unknown-room", TestAction.Message("lost")))
        // Message to known room should arrive
        physical.simulateIncoming(routed("room-1", TestAction.Message("received")))

        withTimeout(1000) {
            job.join()
        }

        val expected: List<TestAction> = listOf(TestAction.Message("received"))
        assertEquals(expected, room1Actions)

        mux.close()
    }

    @Test
    fun closeShutdownsMultiplexer() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)
        mux.connect()

        mux.getOrCreateRoom("room-1")
        mux.getOrCreateRoom("room-2")

        mux.close()

        assertEquals(emptySet(), mux.roomIds())
        assertFailsWith<IllegalStateException> {
            mux.getOrCreateRoom("room-3")
        }
    }

    @Test
    fun virtualConnectionInheritsConnectionState() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)

        val room1 = mux.getOrCreateRoom("room-1")
        assertEquals(ConnectionState.DISCONNECTED, room1.connectionState.value)

        mux.connect()
        // connect() launches a coroutine - advance scheduler to execute it
        testScheduler.advanceUntilIdle()
        assertEquals(ConnectionState.CONNECTED, room1.connectionState.value)

        mux.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, room1.connectionState.value)

        mux.close()
    }

    @Test
    fun hasRoomReturnsFalseForNonexistentRoom() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)
        mux.connect()

        assertFalse(mux.hasRoom("nonexistent"))

        mux.close()
    }

    // --- Concurrency tests ---

    @Test
    fun concurrentGetOrCreateRoomReturnsSameInstance() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)
        mux.connect()

        val results = (1..10).map {
            async { mux.getOrCreateRoom("shared-room") }
        }.awaitAll()

        // All should be the same instance
        val first = results.first()
        results.forEach { assertTrue(it === first) }
        assertEquals(setOf("shared-room"), mux.roomIds())

        mux.close()
    }

    @Test
    fun concurrentRemoveRoomIsSafe() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)
        mux.connect()

        mux.getOrCreateRoom("room-1")

        // Multiple concurrent removes should not throw
        val jobs = (1..5).map {
            async { mux.removeRoom("room-1") }
        }
        jobs.awaitAll()

        assertFalse(mux.hasRoom("room-1"))
        mux.close()
    }

    @Test
    fun rapidCreateRemoveCreateLifecycle() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)
        mux.connect()

        val room1 = mux.getOrCreateRoom("room-1")
        mux.removeRoom("room-1")
        val room2 = mux.getOrCreateRoom("room-1")

        // After remove and recreate, should be a new instance
        assertFalse(room1 === room2)
        assertTrue(mux.hasRoom("room-1"))

        mux.close()
    }

    // --- connect() idempotency tests ---

    @Test
    fun multipleConnectCallsAreIdempotent() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)

        // Call connect multiple times
        mux.connect()
        mux.connect()
        mux.connect()

        testScheduler.advanceUntilIdle()

        // Should still work correctly with single routing
        val room1 = mux.getOrCreateRoom("room-1")
        val room1Actions = mutableListOf<TestAction>()

        val job = launch {
            room1.incoming.take(1).toList(room1Actions)
        }
        yield()

        physical.simulateIncoming(routed("room-1", TestAction.Message("hello")))

        withTimeout(1000) { job.join() }

        val expectedRoom1: List<TestAction> = listOf(TestAction.Message("hello"))
        assertEquals(expectedRoom1, room1Actions)

        mux.close()
    }

    @Test
    fun disconnectAndReconnectWorks() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)

        mux.connect()
        testScheduler.advanceUntilIdle()
        assertEquals(ConnectionState.CONNECTED, mux.connectionState.value)

        mux.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, mux.connectionState.value)

        // Should be able to connect again after disconnect
        mux.connect()
        testScheduler.advanceUntilIdle()
        assertEquals(ConnectionState.CONNECTED, mux.connectionState.value)

        mux.close()
    }

    @Test
    fun removeRoomForNonexistentRoomIsSafe() = runTest {
        val physical = FakeTypedClientConnection<TestAction>()
        val mux = ClientConnectionMultiplexer(physical, this)
        mux.connect()

        // Should not throw
        mux.removeRoom("nonexistent")
        assertEquals(emptySet(), mux.roomIds())

        mux.close()
    }
}
