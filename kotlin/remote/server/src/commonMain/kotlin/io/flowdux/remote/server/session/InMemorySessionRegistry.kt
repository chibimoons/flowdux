package io.flowdux.remote.server.session

import io.flowdux.Action
import io.flowdux.remote.server.connection.TypedServerConnection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe in-memory implementation of [SessionRegistry].
 *
 * Uses a [Mutex] to ensure safe concurrent access to the session map.
 * Suitable for single-node deployments with up to ~100k concurrent connections
 * (depending on hardware and broadcast concurrency configuration).
 *
 * For distributed deployments requiring shared session state across multiple nodes,
 * implement [SessionRegistry] with an external store like Redis.
 *
 * @param A The action type used for typed connections.
 */
class InMemorySessionRegistry<A : Action> : SessionRegistry<A> {

    private val sessions = mutableMapOf<String, TypedServerConnection<A>>()
    private val mutex = Mutex()

    override suspend fun sessionIds(): Set<String> = mutex.withLock {
        sessions.keys.toSet()
    }

    override suspend fun sessionCount(): Int = mutex.withLock {
        sessions.size
    }

    override suspend fun addSession(sessionId: String, connection: TypedServerConnection<A>) {
        mutex.withLock {
            sessions[sessionId] = connection
        }
    }

    override suspend fun removeSession(sessionId: String) {
        mutex.withLock {
            sessions.remove(sessionId)
        }
    }

    override suspend fun getSession(sessionId: String): TypedServerConnection<A>? = mutex.withLock {
        sessions[sessionId]
    }

    override suspend fun getSessions(): Map<String, TypedServerConnection<A>> = mutex.withLock {
        sessions.toMap()
    }
}
