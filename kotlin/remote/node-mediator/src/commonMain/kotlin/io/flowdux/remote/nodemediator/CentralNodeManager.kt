package io.flowdux.remote.nodemediator

import io.flowdux.Action
import io.flowdux.remote.nodemediator.registry.InMemoryRoomRegistry
import io.flowdux.remote.nodemediator.registry.RoomRegistry
import io.flowdux.remote.server.connection.TypedServerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Central-side manager that handles connections from multiple Node servers.
 *
 * Runs alongside the Central Store and routes actions to/from connected nodes.
 * Each node connects via a single [TypedServerConnection] carrying [NodeAction] messages.
 *
 * Usage:
 * ```kotlin
 * val roomRegistry = InMemoryRoomRegistry()
 * val manager = CentralNodeManager<SharedAction>(
 *     roomRegistry = roomRegistry,
 *     onUpstreamAction = { nodeId, roomId, action ->
 *         centralStore.dispatch(action)
 *     },
 * )
 *
 * // In Ktor routing:
 * webSocket("/node/{nodeId}") {
 *     val nodeId = call.parameters["nodeId"]!!
 *     val conn = KtorWebSocketServerConnection(this)
 *         .typedNodeActionJson<SharedAction>()
 *     manager.handleNode(nodeId, conn)
 * }
 *
 * // Route actions to specific rooms
 * manager.sendToRoom("room-1", SyncState(state))
 * ```
 *
 * @param A The type of actions being managed
 * @param roomRegistry Registry mapping rooms to nodes (for [sendToRoom])
 * @param onUpstreamAction Callback invoked when a node sends an action upstream.
 *        Receives the nodeId, roomId, and the action.
 * @param onEvent Optional callback for manager events (node connect/disconnect, errors).
 */
class CentralNodeManager<A : Action>(
    private val roomRegistry: RoomRegistry = InMemoryRoomRegistry(),
    private val onUpstreamAction: suspend (nodeId: String, roomId: String, action: A) -> Unit,
    private val onEvent: ((NodeMediatorEvent) -> Unit)? = null,
) {
    private val mutex = Mutex()
    private val nodes = mutableMapOf<String, NodeEntry>()
    private var closed = false

    private inner class NodeEntry(
        val connection: TypedServerConnection<NodeAction<A>>,
        val job: Job,
    )

    /**
     * Handles a node connection.
     *
     * This function suspends until the node disconnects. It registers the node,
     * starts listening for upstream actions, and cleans up on disconnection.
     *
     * Should be called from within a coroutine (e.g., `launch { manager.handleNode(...) }`).
     *
     * @param nodeId Unique identifier for the connecting node
     * @param connection The typed connection from the node
     */
    suspend fun handleNode(
        nodeId: String,
        connection: TypedServerConnection<NodeAction<A>>,
    ) {
        check(!closed) { "CentralNodeManager is closed" }

        val callerJob = currentCoroutineContext()[Job]!!

        try {
            safeOnEvent(NodeMediatorEvent.NodeConnected(nodeId))

            mutex.withLock {
                nodes[nodeId] = NodeEntry(connection, callerJob)
            }

            connection.incoming.collect { nodeAction ->
                try {
                    onUpstreamAction.invoke(nodeId, nodeAction.roomId, nodeAction.action)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    safeOnEvent(NodeMediatorEvent.CallbackFailed(nodeAction.roomId, e))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            safeOnEvent(NodeMediatorEvent.NodeDisconnected(nodeId, e))
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    nodes.remove(nodeId)
                }
                // Remove room assignments for disconnected node
                val rooms = roomRegistry.getRoomsForNode(nodeId)
                for (roomId in rooms) {
                    roomRegistry.unassignRoom(roomId)
                }
            }
            safeOnEvent(NodeMediatorEvent.NodeDisconnected(nodeId, null))
        }
    }

    /**
     * Sends an action to the node that owns the specified room.
     *
     * Looks up the room in [roomRegistry] to find the target node, then sends
     * a [NodeAction] to that node's connection.
     *
     * @param roomId The room to route to
     * @param action The action to send
     */
    suspend fun sendToRoom(roomId: String, action: A) {
        val nodeId = roomRegistry.getNodeForRoom(roomId) ?: return
        sendToNode(nodeId, roomId, action)
    }

    /**
     * Sends an action to a specific node for a specific room.
     *
     * @param nodeId The target node
     * @param roomId The room identifier
     * @param action The action to send
     */
    suspend fun sendToNode(nodeId: String, roomId: String, action: A) {
        val entry = mutex.withLock { nodes[nodeId] } ?: return
        entry.connection.send(NodeAction(roomId, action))
    }

    /**
     * Broadcasts an action to all connected nodes for a specific room.
     *
     * @param roomId The room identifier
     * @param action The action to send
     */
    suspend fun broadcastToAllNodes(roomId: String, action: A) {
        val entries = mutex.withLock { nodes.values.toList() }
        for (entry in entries) {
            try {
                entry.connection.send(NodeAction(roomId, action))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Individual send failure should not stop broadcasting
            }
        }
    }

    /**
     * Broadcasts an action to every registered room across all nodes.
     *
     * Iterates all room assignments in [roomRegistry] and sends the action
     * to each room's owning node. Individual send failures are silently ignored.
     *
     * @param action The action to broadcast to all rooms
     */
    suspend fun broadcastToAllRooms(action: A) {
        val assignments = roomRegistry.getAllAssignments()
        for ((roomId, nodeId) in assignments) {
            try {
                sendToNode(nodeId, roomId, action)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Individual send failure should not stop broadcasting
            }
        }
    }

    /**
     * Returns the set of currently connected node IDs.
     */
    suspend fun connectedNodeIds(): Set<String> = mutex.withLock {
        nodes.keys.toSet()
    }

    /**
     * Checks if a node is currently connected.
     *
     * @param nodeId The node identifier to check
     * @return true if the node is connected
     */
    suspend fun isNodeConnected(nodeId: String): Boolean = mutex.withLock {
        nodes.containsKey(nodeId)
    }

    /**
     * Closes the manager and disconnects all nodes.
     *
     * Cancels all node handling jobs and clears the registry.
     * After closing, no new nodes can connect.
     */
    suspend fun close() {
        val entries = mutex.withLock {
            closed = true
            val current = nodes.values.toList()
            nodes.clear()
            current
        }
        for (entry in entries) {
            entry.job.cancel()
        }
        val callerJob = currentCoroutineContext()[Job]
        for (entry in entries) {
            if (callerJob != entry.job) {
                entry.job.join()
            }
        }
    }

    private fun safeOnEvent(event: NodeMediatorEvent) {
        try {
            onEvent?.invoke(event)
        } catch (_: Exception) {
            // Never let a faulty event handler break operation
        }
    }
}
