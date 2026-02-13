package io.flowdux.remote.nodemediator

import io.flowdux.Action
import io.flowdux.remote.nodemediator.transport.NodeTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Node-side mediator that routes actions between the Central Store and local room handlers.
 *
 * Each node in a horizontally scaled deployment runs a [NodeMediator] that maintains
 * a single connection to the Central server. Room handlers are registered to receive
 * downstream actions, and can forward upstream actions to the Central Store.
 *
 * Usage:
 * ```kotlin
 * val transport = KtorWebSocketClientConnection.create(centralHost, centralPort, "/node")
 *     .webSocketNodeTransport<SharedAction>()
 *
 * val mediator = NodeMediator(
 *     nodeId = "node-1",
 *     transport = transport,
 *     scope = scope,
 *     onEvent = { event -> logger.info("$event") },
 * )
 * mediator.connect()
 *
 * // Register room handlers
 * mediator.registerRoom("room-1") { action ->
 *     roomStore.dispatch(action)
 * }
 *
 * // Forward actions to central
 * mediator.forwardToCentral("room-1", SomeAction)
 * ```
 *
 * @param A The type of actions being mediated
 * @param nodeId Unique identifier for this node
 * @param transport The transport layer for communicating with the Central server
 * @param scope The coroutine scope for the routing job
 * @param onUnknownRoom Optional callback invoked when an action arrives for an unregistered room.
 *        This allows dynamic room creation on the node.
 * @param onEvent Optional callback for mediator events (message drops, errors).
 *        When provided, transport exceptions are reported via [NodeMediatorEvent.RoutingStopped].
 *        When absent, transport exceptions propagate to [scope] via structured concurrency.
 */
@OptIn(ExperimentalAtomicApi::class)
class NodeMediator<A : Action>(
    private val nodeId: String,
    private val transport: NodeTransport<A>,
    private val scope: CoroutineScope,
    private val onUnknownRoom: (suspend (roomId: String, action: A) -> Unit)? = null,
    private val onEvent: ((NodeMediatorEvent) -> Unit)? = null,
) {
    private val mutex = Mutex()
    private val rooms = mutableMapOf<String, suspend (A) -> Unit>()
    private var routingJob: Job? = null
    private var connectJob: Job? = null
    private val closed = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)

    /**
     * Establishes the connection to the Central server and starts routing.
     *
     * This launches the connection in a background coroutine and starts routing
     * immediately. The connection runs until [disconnect] or [close] is called.
     *
     * This method is idempotent - calling it multiple times has no effect if
     * already connected.
     */
    fun connect() {
        check(!closed.load()) { "NodeMediator is closed" }

        if (!connecting.compareAndSet(expectedValue = false, newValue = true)) {
            return
        }

        startRouting()
        connectJob = scope.launch {
            var connectFailed = false
            try {
                transport.connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connectFailed = true
                if (onEvent != null) {
                    safeOnEvent(NodeMediatorEvent.ConnectionFailed(e))
                } else {
                    throw e
                }
            } finally {
                connecting.store(false)
                if (connectFailed) {
                    routingJob?.cancel()
                    routingJob = null
                }
            }
        }
    }

    /**
     * Disconnects from the Central server and stops routing.
     *
     * Room handlers are preserved so that [connect] can be called again to resume.
     * Use [unregisterRoom] to clean up individual rooms, or [close] to shut down entirely.
     */
    suspend fun disconnect() {
        connecting.store(false)
        val routingSnapshot = routingJob
        val connectSnapshot = connectJob
        routingJob = null
        connectJob = null

        routingSnapshot?.cancel()
        connectSnapshot?.cancel()
        val callerJob = currentCoroutineContext()[Job]
        if (routingSnapshot != null && callerJob != routingSnapshot) {
            routingSnapshot.join()
        }
        if (connectSnapshot != null && callerJob != connectSnapshot) {
            connectSnapshot.join()
        }
        transport.disconnect()
    }

    private fun startRouting() {
        routingJob = scope.launch {
            var transportError: Exception? = null
            try {
                transport.incoming.collect { nodeAction ->
                    val roomId = nodeAction.roomId
                    val handler = mutex.withLock { rooms[roomId] }
                    if (handler != null) {
                        try {
                            handler.invoke(nodeAction.action)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            safeOnEvent(NodeMediatorEvent.CallbackFailed(roomId, e))
                        }
                    } else if (onUnknownRoom != null) {
                        try {
                            onUnknownRoom.invoke(roomId, nodeAction.action)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            safeOnEvent(NodeMediatorEvent.CallbackFailed(roomId, e))
                        }
                    }
                    // else: silent drop
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transportError = e
                if (onEvent != null) {
                    safeOnEvent(NodeMediatorEvent.RoutingStopped(e))
                } else {
                    throw e
                }
            } finally {
                if (transportError != null) {
                    transport.disconnect()
                }
                connecting.store(false)
            }
        }
    }

    /**
     * Registers a room handler to receive downstream actions from the Central server.
     *
     * @param roomId The room identifier
     * @param handler Callback invoked when an action arrives for this room
     */
    suspend fun registerRoom(roomId: String, handler: suspend (A) -> Unit) {
        check(!closed.load()) { "NodeMediator is closed" }
        mutex.withLock {
            rooms[roomId] = handler
        }
        transport.subscribeRoom(roomId)
    }

    /**
     * Unregisters a room handler.
     *
     * @param roomId The room identifier to remove
     */
    suspend fun unregisterRoom(roomId: String) {
        mutex.withLock {
            rooms.remove(roomId)
        }
        transport.unsubscribeRoom(roomId)
    }

    /**
     * Forwards an action from a local room to the Central server (upstream).
     *
     * @param roomId The room identifier the action belongs to
     * @param action The action to forward
     */
    suspend fun forwardToCentral(roomId: String, action: A) {
        check(!closed.load()) { "NodeMediator is closed" }
        transport.send(NodeAction(roomId, action))
    }

    /**
     * Returns the set of currently registered room IDs.
     */
    suspend fun roomIds(): Set<String> = mutex.withLock {
        rooms.keys.toSet()
    }

    /**
     * Checks if a room handler is registered.
     *
     * @param roomId The room identifier to check
     * @return true if a handler is registered for the room
     */
    suspend fun hasRoom(roomId: String): Boolean = mutex.withLock {
        rooms.containsKey(roomId)
    }

    /**
     * Closes the mediator and all room handlers.
     *
     * Cancels the routing job, clears all room handlers, and disconnects from
     * the Central server. After closing, no new rooms can be registered.
     */
    suspend fun close() {
        closed.store(true)
        mutex.withLock {
            rooms.clear()
        }
        connecting.store(false)
        val routingSnapshot = routingJob
        val connectSnapshot = connectJob
        routingJob = null
        connectJob = null

        routingSnapshot?.cancel()
        connectSnapshot?.cancel()
        val callerJob = currentCoroutineContext()[Job]
        if (routingSnapshot != null && callerJob != routingSnapshot) {
            routingSnapshot.join()
        }
        if (connectSnapshot != null && callerJob != connectSnapshot) {
            connectSnapshot.join()
        }
        transport.disconnect()
    }

    private fun safeOnEvent(event: NodeMediatorEvent) {
        try {
            onEvent?.invoke(event)
        } catch (_: Exception) {
            // Never let a faulty event handler break routing
        }
    }
}
