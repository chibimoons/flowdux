package io.flowdux.remote.nodemediator

import io.flowdux.Action
import io.flowdux.State
import io.flowdux.remote.nodemediator.transport.NodeTransport
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.pattern.RoomServer
import io.flowdux.remote.server.pattern.SharedStateServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * Combines [NodeMediator] (Central connection) with [RoomServer] (client session management)
 * into a single API for node servers in a horizontally scaled deployment.
 *
 * Without `NodeRoomServer`, the node must manually handle:
 * - Session tracking (which client is in which room)
 * - State subscription and broadcast to clients
 * - Forwarding client actions to Central
 * - Cleanup on client disconnect
 *
 * `NodeRoomServer` automates all of this by wrapping client connections with
 * [ForwardingConnection] so that every incoming action is transparently forwarded
 * to Central, while [SharedStateServer.handleClient] handles session management
 * and state broadcasting.
 *
 * ## Usage
 *
 * ```kotlin
 * val nodeRoomServer = NodeRoomServer(
 *     nodeId = "node-1",
 *     transport = transport,
 *     roomServer = roomServer,
 *     scope = applicationScope,
 * )
 * nodeRoomServer.connect()
 *
 * webSocket("/ws/{roomId}") {
 *     val roomId = call.parameters["roomId"]!!
 *     val sessionId = UUID.randomUUID().toString()
 *     val connection = KtorWebSocketServerConnection(this)
 *         .typedJson<SharedAction>()
 *
 *     try {
 *         nodeRoomServer.handleClient(roomId, sessionId, connection)
 *     } finally {
 *         withContext(NonCancellable) {
 *             nodeRoomServer.dispatchAndForward(roomId, LeaveRoom(username))
 *             nodeRoomServer.destroyRoomIfEmpty(roomId)
 *         }
 *     }
 * }
 * ```
 *
 * @param S Server-side state type for each room
 * @param A Action type shared between client, node, and central
 * @param nodeId Unique identifier for this node
 * @param transport Transport layer for communicating with the Central server
 * @param roomServer The room server managing local rooms
 * @param scope Coroutine scope for the mediator's routing job
 * @param onEvent Optional callback for mediator events (connection failures, routing errors, etc.)
 */
class NodeRoomServer<S : State, A : Action>(
    nodeId: String,
    transport: NodeTransport<A>,
    private val roomServer: RoomServer<SharedStateServer<S, A>>,
    scope: CoroutineScope,
    onEvent: ((NodeMediatorEvent) -> Unit)? = null,
) {
    /**
     * The underlying [NodeMediator] managing the Central connection.
     *
     * Exposed as read-only for advanced use cases (e.g., checking connection state).
     */
    val mediator: NodeMediator<A>

    init {
        mediator = NodeMediator(
            nodeId = nodeId,
            transport = transport,
            scope = scope,
            onUnknownRoom = { roomId, action ->
                // Central relayed to a room this node doesn't know about yet — auto-create it
                val room = roomServer.getOrCreateRoom(roomId)
                mediator.registerRoom(roomId) { centralAction ->
                    room.store.dispatch(centralAction)
                }
                room.store.dispatch(action)
            },
            onEvent = onEvent,
        )
    }

    /**
     * Establishes the connection to the Central server and starts routing.
     *
     * Delegates to [NodeMediator.connect].
     */
    fun connect() = mediator.connect()

    /**
     * Disconnects from the Central server and stops routing.
     *
     * Room handlers are preserved so that [connect] can be called again to resume.
     */
    suspend fun disconnect() = mediator.disconnect()

    /**
     * Handle a client connection for a specific room.
     *
     * This method:
     * 1. Creates the room if it doesn't exist
     * 2. Registers the room with the mediator (if not already registered)
     * 3. Wraps the connection so incoming actions are automatically forwarded to Central
     * 4. Delegates to [SharedStateServer.handleClient] for session management and state broadcasting
     *
     * Suspends until the connection is closed or cancelled.
     *
     * @param roomId The room identifier
     * @param sessionId Unique identifier for this client session
     * @param connection Typed connection for sending/receiving actions
     */
    suspend fun handleClient(
        roomId: String,
        sessionId: String,
        connection: TypedServerConnection<A>,
    ) {
        val room = roomServer.getOrCreateRoom(roomId)
        if (!mediator.hasRoom(roomId)) {
            mediator.registerRoom(roomId) { centralAction ->
                room.store.dispatch(centralAction)
            }
        }
        val forwarding = ForwardingConnection(connection, mediator, roomId)
        room.handleClient(sessionId, forwarding)
    }

    /**
     * Dispatch an action to a room's local store and forward it to Central.
     *
     * Useful for cleanup actions (e.g., `LeaveRoom`) that need to be applied locally
     * and propagated to other nodes. Forwarding failure is silently ignored to avoid
     * breaking the caller.
     *
     * No-op if the room doesn't exist.
     *
     * @param roomId The room identifier
     * @param action The action to dispatch and forward
     */
    suspend fun dispatchAndForward(roomId: String, action: A) {
        val room = roomServer.getRoom(roomId) ?: return
        room.store.dispatch(action)
        try {
            mediator.forwardToCentral(roomId, action)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Forwarding failure should not break the caller
        }
    }

    /**
     * Get a snapshot of all active room IDs.
     */
    suspend fun roomIds(): Set<String> = roomServer.roomIds()

    /**
     * Destroy a room if it has no active sessions, and unregister it from the mediator.
     *
     * @param roomId The room identifier
     * @return true if the room was empty and destroyed
     */
    suspend fun destroyRoomIfEmpty(roomId: String): Boolean {
        val destroyed = roomServer.destroyRoomIfEmpty(roomId)
        if (destroyed) {
            mediator.unregisterRoom(roomId)
        }
        return destroyed
    }

    /**
     * Clean up all rooms with no active sessions and unregister them from the mediator.
     *
     * @return The list of room IDs that were destroyed
     */
    suspend fun cleanupEmptyRooms(): List<String> {
        val destroyed = roomServer.cleanupEmptyRooms()
        for (roomId in destroyed) {
            mediator.unregisterRoom(roomId)
        }
        return destroyed
    }

    /**
     * Close the node room server.
     *
     * Closes the mediator (disconnecting from Central) and all rooms.
     */
    suspend fun close() {
        mediator.close()
        roomServer.close()
    }
}

/**
 * A [TypedServerConnection] wrapper that intercepts incoming actions and forwards them
 * to Central via [NodeMediator] before passing them through to the underlying connection.
 *
 * This allows [SharedStateServer.handleClient] to transparently receive client actions
 * that are also forwarded to the Central server for cross-node propagation.
 *
 * Forwarding failures are silently caught (except [CancellationException]) to prevent
 * a Central connection issue from disconnecting the client.
 */
private class ForwardingConnection<A : Action>(
    private val delegate: TypedServerConnection<A>,
    private val mediator: NodeMediator<A>,
    private val roomId: String,
) : TypedServerConnection<A> {

    override val incoming: Flow<A> = delegate.incoming.onEach { action ->
        try {
            mediator.forwardToCentral(roomId, action)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Forwarding failure should not disconnect the client
        }
    }

    override suspend fun send(action: A) = delegate.send(action)
}
