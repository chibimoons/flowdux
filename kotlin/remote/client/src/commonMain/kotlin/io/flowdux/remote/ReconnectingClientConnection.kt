package io.flowdux.remote

import io.flowdux.Action
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A [TypedClientConnection] decorator that automatically reconnects when the
 * underlying connection drops.
 *
 * Each reconnection creates a **new** inner connection via [connectionFactory],
 * supporting single-use transport implementations like [io.flowdux.remote.ktor.KtorWebSocketClientConnection].
 *
 * ## Usage
 *
 * ```kotlin
 * val reconnecting = ReconnectingClientConnection(
 *     connectionFactory = { DefaultTypedClientConnection(KtorWebSocketClientConnection(url), codec, messageCodec) },
 *     config = ReconnectionConfig(maxAttempts = 10),
 *     onEvent = { event -> logger.info("Reconnection: $event") },
 * )
 * val middleware = MySyncMiddleware(reconnecting, scope)
 * ```
 *
 * ## Backpressure
 *
 * The shared [incoming] channel uses [Channel.BUFFERED]. When the buffer fills,
 * the inner connection's forwarding coroutine suspends until the consumer collects.
 *
 * ## Lifecycle
 *
 * This connection supports multiple connect/disconnect cycles. The [incoming] channel
 * remains open across [disconnect] calls so that [SyncMiddleware]'s reusable
 * `ServerListenerAction` can continue collecting. Call [disconnect] to stop
 * the reconnection loop and the current inner connection.
 *
 * @param connectionFactory Factory that creates a fresh [TypedClientConnection] for each
 *        connection attempt. Must return a new instance every time.
 * @param config Reconnection strategy configuration.
 * @param onEvent Optional callback for reconnection lifecycle events. Exceptions thrown
 *        by this callback are silently caught to avoid disrupting the reconnection loop.
 */
@OptIn(ExperimentalAtomicApi::class)
class ReconnectingClientConnection<A : Action>(
    private val connectionFactory: () -> TypedClientConnection<A>,
    private val config: ReconnectionConfig = ReconnectionConfig(),
    private val onEvent: ((ReconnectionEvent) -> Unit)? = null,
) : TypedClientConnection<A> {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val incomingChannel = Channel<A>(Channel.BUFFERED)
    override val incoming: Flow<A> = incomingChannel.receiveAsFlow()

    @Volatile
    private var currentConnection: TypedClientConnection<A>? = null

    @Volatile
    private var stopped = false

    /**
     * Send a typed action to the server.
     *
     * @throws IllegalStateException if the connection is not in [ConnectionState.CONNECTED] state.
     */
    override suspend fun send(action: A) {
        check(_connectionState.value == ConnectionState.CONNECTED) { "Cannot send: not connected" }
        val conn = currentConnection ?: error("Cannot send: not connected")
        conn.send(action)
    }

    /**
     * Establish the connection and automatically reconnect on failure.
     *
     * This method suspends until [disconnect] is called or all reconnection
     * attempts are exhausted. The initial connection counts as attempt 0.
     * The attempt counter resets to 0 after each successful connection,
     * so transient failures do not accumulate across stable sessions.
     *
     * [disconnect] sets a stop flag that the reconnection loop checks after each
     * backoff delay. When used via [SyncMiddleware], the coroutine running this
     * method is also cancelled by `stopConnection()`, which immediately interrupts
     * any in-progress backoff delay.
     */
    override suspend fun connect() {
        stopped = false
        var attempt = 0
        var lastException: Exception? = null

        while (!stopped && attempt < config.maxAttempts) {
            if (!applyBackoff(attempt)) return
            val result = attemptConnection(attempt)
            when {
                result.exception is CancellationException -> throw result.exception
                result.exception != null -> {
                    lastException = result.exception
                    if (stopped) return
                    safeOnEvent(ReconnectionEvent.AttemptFailed(attempt, config.maxAttempts, result.exception))
                    attempt++
                }
                stopped -> return
                result.wasConnected -> {
                    attempt = 1
                    lastException = null
                }
                else -> attempt++
            }
        }

        if (!stopped && attempt >= config.maxAttempts) {
            _connectionState.value = ConnectionState.DISCONNECTED
            safeOnEvent(ReconnectionEvent.RetriesExhausted(config.maxAttempts, lastException))
        }
    }

    /**
     * Apply backoff delay for reconnection attempts.
     *
     * @return `false` if the loop should exit (stopped during backoff), `true` to continue.
     */
    private suspend fun applyBackoff(attempt: Int): Boolean {
        if (attempt == 0) {
            _connectionState.value = ConnectionState.CONNECTING
        } else {
            _connectionState.value = ConnectionState.RECONNECTING
            val backoffDelay = config.delayForAttempt(attempt - 1)
            safeOnEvent(ReconnectionEvent.AttemptStarted(attempt, config.maxAttempts, backoffDelay))
            delay(backoffDelay)
        }
        return !stopped
    }

    /**
     * Result of a single connection attempt.
     *
     * @param wasConnected Whether the connection was successfully established before it dropped.
     * @param exception The exception that caused the attempt to fail, or `null` for clean termination.
     */
    private class AttemptResult(val wasConnected: Boolean, val exception: Exception?)

    /**
     * Execute a single connection attempt: create a connection, run it, and return the result.
     */
    private suspend fun attemptConnection(attempt: Int): AttemptResult {
        try {
            val conn = connectionFactory()
            currentConnection = conn
            if (stopped) {
                conn.disconnect()
                currentConnection = null
                return AttemptResult(wasConnected = false, exception = null)
            }
            val wasConnected = runInnerConnection(conn, attempt)
            currentConnection = null
            return AttemptResult(wasConnected = wasConnected, exception = null)
        } catch (e: CancellationException) {
            currentConnection = null
            return AttemptResult(wasConnected = false, exception = e)
        } catch (e: Exception) {
            currentConnection = null
            return AttemptResult(wasConnected = false, exception = e)
        }
    }

    /**
     * Run the inner connection session within a [supervisorScope], forwarding
     * incoming actions and monitoring state until the connection terminates.
     *
     * @return `true` if the connection was successfully established at some point.
     */
    private suspend fun runInnerConnection(conn: TypedClientConnection<A>, attempt: Int): Boolean {
        val wasConnected = AtomicBoolean(false)
        supervisorScope {
            val forwardJob: Job = launch { forwardIncoming(conn) }
            val stateJob: Job = launch { monitorState(conn, attempt, wasConnected) }
            try {
                conn.connect()
            } finally {
                stateJob.cancel()
                forwardJob.cancel()
            }
        }
        return wasConnected.load()
    }

    /** Forward incoming actions from inner connection to the shared channel. */
    private suspend fun forwardIncoming(conn: TypedClientConnection<A>) {
        conn.incoming.collect { action ->
            try {
                incomingChannel.send(action)
            } catch (_: ClosedSendChannelException) {
                // Channel closed — stop forwarding
            }
        }
    }

    /** Monitor inner connection state and propagate to the outer connection state. */
    private suspend fun monitorState(conn: TypedClientConnection<A>, attempt: Int, wasConnected: AtomicBoolean) {
        conn.connectionState.collect { state ->
            if (stopped) return@collect
            when (state) {
                ConnectionState.CONNECTED -> {
                    wasConnected.store(true)
                    _connectionState.value = ConnectionState.CONNECTED
                    safeOnEvent(ReconnectionEvent.Connected(attempt))
                }
                ConnectionState.CONNECTING -> {
                    if (_connectionState.value != ConnectionState.RECONNECTING) {
                        _connectionState.value = ConnectionState.CONNECTING
                    }
                }
                ConnectionState.DISCONNECTED -> {
                    // Will be handled when connect() returns
                }
                ConnectionState.RECONNECTING -> {
                    _connectionState.value = ConnectionState.RECONNECTING
                }
            }
        }
    }

    /**
     * Stop the reconnection loop and disconnect the current inner connection.
     *
     * Sets a stop flag that the reconnection loop checks after each backoff
     * delay and before each connection attempt. When used via [SyncMiddleware],
     * `stopConnection()` also cancels the coroutine running [connect], which
     * immediately interrupts any in-progress backoff delay.
     *
     * The shared [incoming] channel remains open so that this instance can be
     * reused with a subsequent [connect] call (matching [SyncMiddleware]'s
     * `startConnection()`/`stopConnection()` lifecycle).
     */
    override suspend fun disconnect() {
        stopped = true
        _connectionState.value = ConnectionState.DISCONNECTED
        currentConnection?.disconnect()
        currentConnection = null
    }

    private fun safeOnEvent(event: ReconnectionEvent) {
        try {
            onEvent?.invoke(event)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Swallow callback errors to avoid disrupting the reconnection loop.
        }
    }
}
