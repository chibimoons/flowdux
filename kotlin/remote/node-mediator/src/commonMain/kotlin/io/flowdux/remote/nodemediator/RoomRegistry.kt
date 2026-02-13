package io.flowdux.remote.nodemediator

/**
 * Abstraction for tracking which rooms are assigned to which nodes.
 *
 * Used by [CentralNodeManager] to route actions to the correct node
 * based on room assignment.
 *
 * The default implementation is [InMemoryRoomRegistry] for single-process deployments.
 * For distributed deployments, implement this interface with Redis or other external stores.
 */
interface RoomRegistry {
    /**
     * Get the node ID that a room is assigned to.
     *
     * @param roomId The room identifier to look up.
     * @return The node ID if the room is assigned, null otherwise.
     */
    suspend fun getNodeForRoom(roomId: String): String?

    /**
     * Assign a room to a specific node.
     *
     * @param roomId The room identifier to assign.
     * @param nodeId The node to assign the room to.
     */
    suspend fun assignRoom(roomId: String, nodeId: String)

    /**
     * Remove a room assignment.
     *
     * @param roomId The room identifier to unassign.
     */
    suspend fun unassignRoom(roomId: String)

    /**
     * Get all rooms assigned to a specific node.
     *
     * @param nodeId The node identifier.
     * @return Set of room IDs assigned to the node.
     */
    suspend fun getRoomsForNode(nodeId: String): Set<String>

    /**
     * Get all room-to-node assignments.
     *
     * @return Map of room ID to node ID.
     */
    suspend fun getAllAssignments(): Map<String, String>

    /**
     * Get all node IDs that have at least one room assigned.
     *
     * @return Set of node IDs with assignments.
     */
    suspend fun nodeIds(): Set<String>
}
