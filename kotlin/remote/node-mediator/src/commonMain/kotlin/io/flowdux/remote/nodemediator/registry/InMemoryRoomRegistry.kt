package io.flowdux.remote.nodemediator.registry

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe in-memory implementation of [RoomRegistry].
 *
 * Uses a [Mutex] to ensure safe concurrent access to the assignment map.
 * Suitable for single-process deployments where the Central Store and
 * [CentralNodeManager] run in the same process.
 *
 * For distributed deployments requiring shared room assignment state,
 * implement [RoomRegistry] with an external store like Redis.
 */
class InMemoryRoomRegistry : RoomRegistry {

    private val assignments = mutableMapOf<String, String>()
    private val mutex = Mutex()

    override suspend fun getNodeForRoom(roomId: String): String? = mutex.withLock {
        assignments[roomId]
    }

    override suspend fun assignRoom(roomId: String, nodeId: String) {
        mutex.withLock {
            assignments[roomId] = nodeId
        }
    }

    override suspend fun unassignRoom(roomId: String) {
        mutex.withLock {
            assignments.remove(roomId)
        }
    }

    override suspend fun getRoomsForNode(nodeId: String): Set<String> = mutex.withLock {
        assignments.filterValues { it == nodeId }.keys.toSet()
    }

    override suspend fun getAllAssignments(): Map<String, String> = mutex.withLock {
        assignments.toMap()
    }

    override suspend fun nodeIds(): Set<String> = mutex.withLock {
        assignments.values.toSet()
    }
}
