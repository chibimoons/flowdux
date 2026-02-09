package io.flowdux.remote.server

import io.flowdux.remote.server.pattern.createPerClientRoomServer
import io.flowdux.remote.server.pattern.createRoomServer
import io.flowdux.remote.server.pattern.createSharedStateRoomServer
import io.flowdux.remote.server.pattern.createSharedStateServer
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomServerTest {

    @Test
    fun `getOrCreateRoom creates room on first access`() = runTest {
        val roomServer = createRoomServer(backgroundScope) { roomId ->
            createSharedStateServer(
                initialState = ServerState(count = roomId.hashCode()),
                reducer = serverReducer,
                stateMapper = { ServerAction.SyncState(it) },
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )
        }

        assertEquals(0, roomServer.roomCount())

        val room1 = roomServer.getOrCreateRoom("room-1")
        assertNotNull(room1)
        assertEquals(1, roomServer.roomCount())
        assertTrue(roomServer.roomIds().contains("room-1"))

        roomServer.close()
    }

    @Test
    fun `getOrCreateRoom returns existing room`() = runTest {
        val roomServer = createRoomServer(backgroundScope) { roomId ->
            createSharedStateServer(
                initialState = ServerState(count = roomId.hashCode()),
                reducer = serverReducer,
                stateMapper = { ServerAction.SyncState(it) },
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )
        }

        val room1 = roomServer.getOrCreateRoom("room-1")
        val room1Again = roomServer.getOrCreateRoom("room-1")

        // Same instance
        assertTrue(room1 === room1Again)
        assertEquals(1, roomServer.roomCount())

        roomServer.close()
    }

    @Test
    fun `multiple rooms are isolated`() = runTest {
        val roomServer = createRoomServer(backgroundScope) { roomId ->
            createSharedStateServer(
                initialState = ServerState(),
                reducer = serverReducer,
                stateMapper = { ServerAction.SyncState(it) },
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )
        }

        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()

        val room1 = roomServer.getOrCreateRoom("room-1")
        val room2 = roomServer.getOrCreateRoom("room-2")

        // Connect clients to different rooms
        val job1 = backgroundScope.launch { room1.handleClient("client-1", conn1) }
        val job2 = backgroundScope.launch { room2.handleClient("client-2", conn2) }
        delay(100)

        // Client 1 sends action to room-1
        conn1.simulateClientAction(ServerAction.ClientAdd(10))
        delay(100)

        // Client 2 sends action to room-2
        conn2.simulateClientAction(ServerAction.ClientAdd(20))
        delay(100)

        // Rooms have independent state
        assertEquals(10, room1.currentState.count)
        assertEquals(20, room2.currentState.count)

        job1.cancel()
        job2.cancel()
        roomServer.close()
    }

    @Test
    fun `destroyRoom closes and removes room`() = runTest {
        val roomServer = createRoomServer(backgroundScope) { roomId ->
            createSharedStateServer(
                initialState = ServerState(),
                reducer = serverReducer,
                stateMapper = { ServerAction.SyncState(it) },
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )
        }

        roomServer.getOrCreateRoom("room-1")
        assertEquals(1, roomServer.roomCount())

        val destroyed = roomServer.destroyRoom("room-1")
        assertTrue(destroyed)
        assertEquals(0, roomServer.roomCount())

        // Destroying non-existent room returns false
        val destroyedAgain = roomServer.destroyRoom("room-1")
        assertFalse(destroyedAgain)

        roomServer.close()
    }

    @Test
    fun `destroyRoomIfEmpty only destroys empty rooms`() = runTest {
        val roomServer = createRoomServer(backgroundScope) { roomId ->
            createSharedStateServer(
                initialState = ServerState(),
                reducer = serverReducer,
                stateMapper = { ServerAction.SyncState(it) },
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )
        }

        val conn = MockTypedServerConnection<ServerAction>()
        val room = roomServer.getOrCreateRoom("room-1")

        // Connect a client
        val clientJob = backgroundScope.launch {
            room.handleClient("client-1", conn)
        }
        delay(100)

        // Room has a session, should not be destroyed
        val destroyed = roomServer.destroyRoomIfEmpty("room-1")
        assertFalse(destroyed)
        assertEquals(1, roomServer.roomCount())

        // Disconnect client
        clientJob.cancelAndJoin()
        delay(100)

        // Now room is empty, should be destroyed
        val destroyedNow = roomServer.destroyRoomIfEmpty("room-1")
        assertTrue(destroyedNow)
        assertEquals(0, roomServer.roomCount())

        roomServer.close()
    }

    @Test
    fun `cleanupEmptyRooms removes all empty rooms`() = runTest {
        val roomServer = createRoomServer(backgroundScope) { roomId ->
            createSharedStateServer(
                initialState = ServerState(),
                reducer = serverReducer,
                stateMapper = { ServerAction.SyncState(it) },
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )
        }

        val conn = MockTypedServerConnection<ServerAction>()

        val room1 = roomServer.getOrCreateRoom("room-1")
        roomServer.getOrCreateRoom("room-2")
        roomServer.getOrCreateRoom("room-3")

        // Connect client to room-1 only
        val clientJob = backgroundScope.launch {
            room1.handleClient("client-1", conn)
        }
        delay(100)

        assertEquals(3, roomServer.roomCount())

        // Cleanup should remove room-2 and room-3 but keep room-1
        val destroyed = roomServer.cleanupEmptyRooms()
        assertEquals(2, destroyed.size)
        assertTrue(destroyed.containsAll(listOf("room-2", "room-3")))
        assertEquals(1, roomServer.roomCount())
        assertTrue(roomServer.roomIds().contains("room-1"))

        clientJob.cancel()
        roomServer.close()
    }

    @Test
    fun `getRoom returns null for non-existent room`() = runTest {
        val roomServer = createRoomServer(backgroundScope) { roomId ->
            createSharedStateServer(
                initialState = ServerState(),
                reducer = serverReducer,
                stateMapper = { ServerAction.SyncState(it) },
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )
        }

        assertNull(roomServer.getRoom("non-existent"))

        roomServer.getOrCreateRoom("room-1")
        assertNotNull(roomServer.getRoom("room-1"))

        roomServer.close()
    }

    @Test
    fun `createSharedStateRoomServer with simple config`() = runTest {
        val roomServer = createSharedStateRoomServer(
            initialStateFactory = { roomId -> ServerState(count = roomId.length) },
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val room1 = roomServer.getOrCreateRoom("abc")
        val room2 = roomServer.getOrCreateRoom("abcdef")

        // Initial state based on roomId
        assertEquals(3, room1.currentState.count)
        assertEquals(6, room2.currentState.count)

        roomServer.close()
    }

    @Test
    fun `createPerClientRoomServer creates rooms with PerClientServer`() = runTest {
        val roomServer = createPerClientRoomServer(
            initialStateFactory = { roomId, sessionId -> ServerState(count = roomId.length + sessionId.length) },
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()

        val room = roomServer.getOrCreateRoom("abc") // length 3

        // Connect clients to the same room
        val job1 = backgroundScope.launch { room.handleClient("p1", conn1) } // length 2
        val job2 = backgroundScope.launch { room.handleClient("player2", conn2) } // length 7
        delay(100)

        // Each client has independent state
        // Client 1: 3 + 2 = 5
        // Client 2: 3 + 7 = 10
        val sync1 = conn1.sentActions.filterIsInstance<ServerAction.SyncState>().first()
        val sync2 = conn2.sentActions.filterIsInstance<ServerAction.SyncState>().first()

        assertEquals(5, sync1.state.count)
        assertEquals(10, sync2.state.count)

        job1.cancel()
        job2.cancel()
        roomServer.close()
    }

    @Test
    fun `close shuts down all rooms`() = runTest {
        val roomServer = createRoomServer(backgroundScope) { roomId ->
            createSharedStateServer(
                initialState = ServerState(),
                reducer = serverReducer,
                stateMapper = { ServerAction.SyncState(it) },
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )
        }

        roomServer.getOrCreateRoom("room-1")
        roomServer.getOrCreateRoom("room-2")
        assertEquals(2, roomServer.roomCount())

        roomServer.close()
        assertEquals(0, roomServer.roomCount())
    }
}
