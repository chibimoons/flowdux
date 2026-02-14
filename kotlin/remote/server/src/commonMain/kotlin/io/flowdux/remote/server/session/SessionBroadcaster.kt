package io.flowdux.remote.server.session

import io.flowdux.Action
import io.flowdux.remote.server.connection.TypedServerConnection
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow

/**
 * Configuration for broadcast behavior.
 *
 * @property concurrency Number of parallel sends during broadcast.
 *   - `1` = sequential broadcast (default, lowest memory overhead)
 *   - `16-64` = recommended for 10k-100k clients
 *   - Higher values may improve throughput but increase memory usage
 */
data class BroadcastConfig(
    val concurrency: Int = 1,
) {
    init {
        require(concurrency >= 1) { "concurrency must be at least 1" }
    }

    companion object {
        /** Sequential broadcast (one connection at a time). */
        val Sequential = BroadcastConfig(concurrency = 1)

        /** Default parallel broadcast for moderate scale. */
        val Default = BroadcastConfig(concurrency = 16)

        /** High-throughput parallel broadcast for large scale. */
        val HighThroughput = BroadcastConfig(concurrency = 64)
    }
}

/**
 * Handles broadcasting actions to multiple client sessions.
 *
 * Supports both sequential and parallel broadcast modes:
 * - Sequential: sends to each client one at a time (lowest memory)
 * - Parallel: uses [kotlinx.coroutines.flow.flatMapMerge] for concurrent sends
 *
 * Individual connection failures are isolated and do not affect other sends.
 *
 * @param A The action type.
 * @param registry The session registry to read connections from.
 * @param config Broadcast configuration (concurrency level).
 */
class SessionBroadcaster<A : Action>(
    val registry: SessionRegistry<A>,
    private val config: BroadcastConfig = BroadcastConfig.Sequential,
) {

    /**
     * Send an action to a specific client by session ID.
     * No-op if the session does not exist.
     *
     * @param sessionId The target session ID.
     * @param action The action to send.
     */
    suspend fun sendToClient(sessionId: String, action: A) {
        val connection = registry.getSession(sessionId) ?: return
        try {
            connection.send(action)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Isolate send failures
        }
    }

    /**
     * Send an action to all connected clients.
     *
     * Uses parallel sending if [BroadcastConfig.concurrency] > 1.
     * Errors on individual connections are caught and do not affect others.
     *
     * @param action The action to broadcast.
     */
    suspend fun broadcast(action: A) {
        val connections = registry.getSessions().values.toList()
        forEachConcurrent(connections) { connection ->
            sendSafe(connection, action)
        }
    }

    /**
     * Send a per-session action to each connected client.
     *
     * For each session, calls [mapper] to produce the action for that session.
     * If [mapper] returns `null`, the session is skipped.
     *
     * Uses parallel sending if [BroadcastConfig.concurrency] > 1.
     * Errors on individual connections are caught and do not affect others.
     *
     * @param mapper Function that produces an action for each session ID, or null to skip.
     */
    suspend fun sendPerSession(mapper: (sessionId: String) -> A?) {
        val sessions = registry.getSessions().entries.toList()
        forEachConcurrent(sessions) { (sessionId, connection) ->
            val action = mapper(sessionId) ?: return@forEachConcurrent
            sendSafe(connection, action)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> forEachConcurrent(
        items: Collection<T>,
        action: suspend (T) -> Unit,
    ) {
        if (config.concurrency == 1) {
            for (item in items) {
                action(item)
            }
        } else {
            items.asFlow()
                .flatMapMerge(config.concurrency) { item ->
                    flow {
                        action(item)
                        emit(Unit)
                    }
                }
                .collect()
        }
    }

    private suspend fun sendSafe(connection: TypedServerConnection<A>, action: A) {
        try {
            connection.send(action)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Isolate per-client send failures
        }
    }
}
