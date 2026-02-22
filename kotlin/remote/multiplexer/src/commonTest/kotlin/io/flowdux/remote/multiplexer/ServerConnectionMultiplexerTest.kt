package io.flowdux.remote.multiplexer

import io.flowdux.Action
import io.flowdux.remote.server.connection.TypedServerConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

class ServerConnectionMultiplexerTest {

    @Serializable
    sealed interface TestAction : Action {
        @Serializable data class Message(val text: String) : TestAction
        @Serializable data class Ping(val id: Int) : TestAction
    }

    private fun routed(roomId: String, action: TestAction): RoutedAction<TestAction> =
        RoutedAction(roomId, action)

    private class FakeTypedServerConnection<A : Action> : TypedServerConnection<RoutedAction<A>> {
        override val isActive: Boolean = true
        val incomingFlow = MutableSharedFlow<RoutedAction<A>>(extraBufferCapacity = 64)
        val sentActions = mutableListOf<RoutedAction<A>>()

        override val incoming: Flow<RoutedAction<A>> = incomingFlow

        override suspend fun send(action: RoutedAction<A>) {
            sentActions.add(action)
        }

        fun simulateIncoming(action: RoutedAction<A>) {
            incomingFlow.tryEmit(action)
        }
    }

    @Test
    fun getOrCreateRoomCreatesNewRoom() = runTest {
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

        val room1 = mux.getOrCreateRoom("room-1")
        assertTrue(mux.hasRoom("room-1"))
        assertEquals(setOf("room-1"), mux.roomIds())

        mux.close()
    }

    @Test
    fun getOrCreateRoomReturnsSameInstance() = runTest {
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

        val room1a = mux.getOrCreateRoom("room-1")
        val room1b = mux.getOrCreateRoom("room-1")
        assertTrue(room1a === room1b)

        mux.close()
    }

    @Test
    fun multipleRoomsCanBeCreated() = runTest {
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

        mux.getOrCreateRoom("room-1")
        mux.getOrCreateRoom("room-2")
        mux.getOrCreateRoom("room-3")

        assertEquals(setOf("room-1", "room-2", "room-3"), mux.roomIds())

        mux.close()
    }

    @Test
    fun removeRoomRemovesRoom() = runTest {
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

        mux.getOrCreateRoom("room-1")
        assertTrue(mux.hasRoom("room-1"))

        mux.removeRoom("room-1")
        assertFalse(mux.hasRoom("room-1"))
        assertEquals(emptySet(), mux.roomIds())

        mux.close()
    }

    @Test
    fun incomingActionsRoutedToCorrectRoom() = runTest {
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

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
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

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
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

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
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

        mux.getOrCreateRoom("room-1")
        mux.getOrCreateRoom("room-2")

        mux.close()

        assertEquals(emptySet(), mux.roomIds())
        assertFailsWith<IllegalStateException> {
            mux.getOrCreateRoom("room-3")
        }
    }

    @Test
    fun hasRoomReturnsFalseForNonexistentRoom() = runTest {
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

        assertFalse(mux.hasRoom("nonexistent"))

        mux.close()
    }

    // --- Concurrency tests ---

    @Test
    fun concurrentGetOrCreateRoomReturnsSameInstance() = runTest {
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

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
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

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
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

        val room1 = mux.getOrCreateRoom("room-1")
        mux.removeRoom("room-1")
        val room2 = mux.getOrCreateRoom("room-1")

        // After remove and recreate, should be a new instance
        assertFalse(room1 === room2)
        assertTrue(mux.hasRoom("room-1"))

        mux.close()
    }

    // --- onUnknownRoom callback tests ---

    @Test
    fun onUnknownRoomCallbackReceivesCorrectRoomIdAndAction() = runTest {
        val physical = FakeTypedServerConnection<TestAction>()
        val receivedCalls = mutableListOf<Pair<String, TestAction>>()

        val mux = ServerConnectionMultiplexer(
            physicalConnection = physical,
            scope = this,
            onUnknownRoom = { roomId, action -> receivedCalls.add(roomId to action) },
        )

        yield()

        physical.simulateIncoming(routed("new-room", TestAction.Message("hello")))
        physical.simulateIncoming(routed("another-room", TestAction.Ping(42)))
        yield()

        assertEquals(2, receivedCalls.size)
        assertEquals("new-room" to TestAction.Message("hello"), receivedCalls[0])
        assertEquals("another-room" to TestAction.Ping(42), receivedCalls[1])

        mux.close()
    }

    @Test
    fun onUnknownRoomCallbackExceptionDoesNotStopRouting() = runTest {
        val physical = FakeTypedServerConnection<TestAction>()
        var callCount = 0

        val mux = ServerConnectionMultiplexer(
            physicalConnection = physical,
            scope = this,
            onUnknownRoom = { _, _ ->
                callCount++
                if (callCount == 1) throw RuntimeException("callback error")
            },
        )

        // Also create a known room to verify routing continues
        val room1 = mux.getOrCreateRoom("room-1")
        val room1Actions = mutableListOf<TestAction>()

        val job = launch {
            room1.incoming.take(1).toList(room1Actions)
        }
        yield()

        // First unknown room triggers exception in callback
        physical.simulateIncoming(routed("unknown-1", TestAction.Message("boom")))
        yield()

        // Second unknown room should still be processed
        physical.simulateIncoming(routed("unknown-2", TestAction.Message("ok")))
        yield()

        // Known room should still receive messages
        physical.simulateIncoming(routed("room-1", TestAction.Message("hello")))

        withTimeout(1000) { job.join() }

        assertEquals(2, callCount)
        val expectedRoom1: List<TestAction> = listOf(TestAction.Message("hello"))
        assertEquals(expectedRoom1, room1Actions)

        mux.close()
    }

    @Test
    fun concurrentGetOrCreateRoomAndCloseDoesNotCorruptState() = runTest {
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

        // Race: coroutines create rooms while another closes the multiplexer.
        // getOrCreateRoom should either succeed or throw ISE — never corrupt state.
        val createJobs = (1..10).map { i ->
            async {
                // Yield to allow interleaving with the close coroutine
                yield()
                try {
                    mux.getOrCreateRoom("room-$i")
                    true
                } catch (_: IllegalStateException) {
                    false // Expected: "Multiplexer is closed"
                }
            }
        }
        val closeJob = async {
            yield()
            mux.close()
        }

        // Advance all coroutines to ensure interleaving
        createJobs.awaitAll()
        closeJob.await()

        // After close, new rooms must be rejected
        assertFailsWith<IllegalStateException> {
            mux.getOrCreateRoom("room-after-close")
        }
    }

    @Test
    fun concurrentCloseIsSafe() = runTest {
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

        mux.getOrCreateRoom("room-1")
        mux.getOrCreateRoom("room-2")

        // Multiple concurrent close calls should not throw
        val jobs = (1..5).map {
            async { mux.close() }
        }
        jobs.awaitAll()

        assertEquals(emptySet(), mux.roomIds())
        assertFailsWith<IllegalStateException> {
            mux.getOrCreateRoom("room-3")
        }
    }

    @Test
    fun removeRoomWhileRoutingMessagesIsSafe() = runTest {
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

        val room1 = mux.getOrCreateRoom("room-1")
        mux.getOrCreateRoom("room-2")

        // Start collecting from room-1
        val room1Actions = mutableListOf<TestAction>()
        val collectJob = launch {
            room1.incoming.collect { room1Actions.add(it) }
        }
        yield()

        // Route a message, then concurrently remove the room while routing continues
        physical.simulateIncoming(routed("room-1", TestAction.Message("msg-1")))
        yield()

        // Concurrent: remove room while routing job may still reference it
        val removeJob = async {
            mux.removeRoom("room-1")
        }
        physical.simulateIncoming(routed("room-1", TestAction.Message("during-remove")))
        yield()

        removeJob.await()
        assertFalse(mux.hasRoom("room-1"))

        // Messages to removed room should be silently dropped (no crash)
        physical.simulateIncoming(routed("room-1", TestAction.Message("after-remove")))
        yield()

        // room-2 should still work normally after room-1 removal
        val room2 = mux.getOrCreateRoom("room-2")
        val room2Actions = mutableListOf<TestAction>()
        val job = launch {
            room2.incoming.take(1).toList(room2Actions)
        }
        yield()

        physical.simulateIncoming(routed("room-2", TestAction.Message("still works")))
        withTimeout(1000) { job.join() }

        val expected: List<TestAction> = listOf(TestAction.Message("still works"))
        assertEquals(expected, room2Actions)

        collectJob.cancel()
        mux.close()
    }

    @Test
    fun removeRoomForNonexistentRoomIsSafe() = runTest {
        val physical = FakeTypedServerConnection<TestAction>()
        val mux = ServerConnectionMultiplexer(physical, this)

        // Should not throw
        mux.removeRoom("nonexistent")
        assertEquals(emptySet(), mux.roomIds())

        mux.close()
    }
}
