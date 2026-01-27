package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.State
import io.flowdux.Store
import io.flowdux.remote.ActionCodec
import io.flowdux.remote.JsonMessageCodec
import io.flowdux.remote.MessageCodec
import kotlinx.coroutines.delay

/**
 * Handles a single client session on the server side.
 *
 * Holds an internal flowdux [Store] and a [ResponseCollector] to capture
 * actions produced by the reducer after dispatching a client's action.
 *
 * Example:
 * ```kotlin
 * val handler = ServerSessionHandler(
 *     storeFactory = {
 *         val collector = ResponseCollector<ServerState, ServerAction>()
 *         val store = createStore(
 *             initialState = ServerState(),
 *             reducer = serverReducer,
 *             logger = collector,
 *             scope = scope,
 *         )
 *         Pair(store, collector)
 *     },
 *     actionCodec = ServerActionCodec(),
 * )
 * handler.initialize()
 *
 * // In WebSocket handler:
 * val response = handler.handleMessage(incomingRaw)
 * session.send(response)
 * ```
 *
 * @param storeFactory Factory that creates a Store and its ResponseCollector pair.
 * @param actionCodec Codec for serializing/deserializing actions.
 * @param messageCodec Codec for wire-level message framing.
 * @param processingDelayMs Delay after dispatching to allow middleware processing.
 */
class ServerSessionHandler<S : State, A : Action>(
    private val storeFactory: () -> Pair<Store<S, A>, ResponseCollector<S, A>>,
    private val actionCodec: ActionCodec<A>,
    private val messageCodec: MessageCodec = JsonMessageCodec(),
    private val processingDelayMs: Long = 50L,
) {
    private lateinit var store: Store<S, A>
    private lateinit var collector: ResponseCollector<S, A>

    /**
     * Initialize the internal store and collector.
     * Must be called before [handleMessage].
     */
    fun initialize() {
        val (s, c) = storeFactory()
        store = s
        collector = c
    }

    /**
     * Process an incoming client message and return the server response.
     *
     * @param raw The raw wire message from the client.
     * @return The encoded server response with resulting actions.
     */
    suspend fun handleMessage(raw: String): String {
        val actionJson = messageCodec.decodeActionFromClient(raw)
        val action = actionCodec.decode(actionJson)

        store.dispatch(action)

        // Allow middleware and reducer processing to complete
        delay(processingDelayMs)

        val resultActions = collector.drain()
        val actionJsons = resultActions.map { actionCodec.encode(it) }

        return messageCodec.encodeServerResponse(actionJsons)
    }

    /**
     * Close the internal store and release resources.
     */
    fun close() {
        if (::store.isInitialized) {
            store.close()
        }
    }
}
