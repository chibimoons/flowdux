package io.flowdux.remote.nodemediator

import io.flowdux.remote.nodemediator.registry.InMemoryRoomRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryRoomRegistryTest {

    @Test
    fun assignAndGetNodeForRoom() = runTest {
        val registry = InMemoryRoomRegistry()
        registry.assignRoom("room-1", "node-A")

        assertEquals("node-A", registry.getNodeForRoom("room-1"))
    }

    @Test
    fun getNodeForUnknownRoomReturnsNull() = runTest {
        val registry = InMemoryRoomRegistry()

        assertNull(registry.getNodeForRoom("unknown"))
    }

    @Test
    fun unassignRoom() = runTest {
        val registry = InMemoryRoomRegistry()
        registry.assignRoom("room-1", "node-A")
        registry.unassignRoom("room-1")

        assertNull(registry.getNodeForRoom("room-1"))
    }

    @Test
    fun unassignNonexistentRoomDoesNothing() = runTest {
        val registry = InMemoryRoomRegistry()
        registry.unassignRoom("nonexistent") // Should not throw
    }

    @Test
    fun reassignRoomToNewNode() = runTest {
        val registry = InMemoryRoomRegistry()
        registry.assignRoom("room-1", "node-A")
        registry.assignRoom("room-1", "node-B")

        assertEquals("node-B", registry.getNodeForRoom("room-1"))
    }

    @Test
    fun getRoomsForNode() = runTest {
        val registry = InMemoryRoomRegistry()
        registry.assignRoom("room-1", "node-A")
        registry.assignRoom("room-2", "node-A")
        registry.assignRoom("room-3", "node-B")

        assertEquals(setOf("room-1", "room-2"), registry.getRoomsForNode("node-A"))
        assertEquals(setOf("room-3"), registry.getRoomsForNode("node-B"))
    }

    @Test
    fun getRoomsForUnknownNodeReturnsEmpty() = runTest {
        val registry = InMemoryRoomRegistry()

        assertEquals(emptySet(), registry.getRoomsForNode("unknown"))
    }

    @Test
    fun getAllAssignments() = runTest {
        val registry = InMemoryRoomRegistry()
        registry.assignRoom("room-1", "node-A")
        registry.assignRoom("room-2", "node-B")
        registry.assignRoom("room-3", "node-A")

        val assignments = registry.getAllAssignments()
        assertEquals(
            mapOf("room-1" to "node-A", "room-2" to "node-B", "room-3" to "node-A"),
            assignments,
        )
    }

    @Test
    fun getAllAssignmentsReturnsSnapshot() = runTest {
        val registry = InMemoryRoomRegistry()
        registry.assignRoom("room-1", "node-A")

        val snapshot = registry.getAllAssignments()
        registry.assignRoom("room-2", "node-B")

        // Snapshot should not be affected by later modifications
        assertEquals(1, snapshot.size)
    }

    @Test
    fun nodeIds() = runTest {
        val registry = InMemoryRoomRegistry()
        registry.assignRoom("room-1", "node-A")
        registry.assignRoom("room-2", "node-B")
        registry.assignRoom("room-3", "node-A")

        assertEquals(setOf("node-A", "node-B"), registry.nodeIds())
    }

    @Test
    fun nodeIdsEmptyWhenNoAssignments() = runTest {
        val registry = InMemoryRoomRegistry()

        assertEquals(emptySet(), registry.nodeIds())
    }

    @Test
    fun concurrentAssignments() = runTest {
        val registry = InMemoryRoomRegistry()
        val deferreds = (1..100).map { i ->
            async {
                registry.assignRoom("room-$i", "node-${i % 3}")
            }
        }
        deferreds.awaitAll()

        val all = registry.getAllAssignments()
        assertEquals(100, all.size)
    }

    @Test
    fun concurrentAssignAndUnassign() = runTest {
        val registry = InMemoryRoomRegistry()

        // Assign 50 rooms
        (1..50).map { i ->
            async { registry.assignRoom("room-$i", "node-A") }
        }.awaitAll()

        // Concurrently unassign odd rooms and reassign even rooms
        val ops = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
        for (i in 1..50) {
            if (i % 2 == 1) {
                ops.add(async { registry.unassignRoom("room-$i") })
            } else {
                ops.add(async { registry.assignRoom("room-$i", "node-B") })
            }
        }
        ops.awaitAll()

        // Even rooms should be assigned to node-B, odd rooms unassigned
        for (i in 1..50) {
            if (i % 2 == 1) {
                assertNull(registry.getNodeForRoom("room-$i"), "room-$i should be unassigned")
            } else {
                assertEquals("node-B", registry.getNodeForRoom("room-$i"), "room-$i should be node-B")
            }
        }
    }

    @Test
    fun nodeIdsUpdatesAfterUnassign() = runTest {
        val registry = InMemoryRoomRegistry()
        registry.assignRoom("room-1", "node-A")
        registry.assignRoom("room-2", "node-B")

        assertEquals(setOf("node-A", "node-B"), registry.nodeIds())

        registry.unassignRoom("room-2")
        assertEquals(setOf("node-A"), registry.nodeIds())
    }
}
