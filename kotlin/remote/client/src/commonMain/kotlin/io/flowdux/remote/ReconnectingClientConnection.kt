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
 * @param connectionFactory Factory that creates a fresh [TypedClientConnection] for each
 *        connection attempt. Must return a new instance every time.
 * @param config Reconnection strategy configuration.
 * @param onEvent Optional callback for reconnection lifecycle events. Exceptions thrown
 *        by this callback are silently caught to avoid disrupting the reconnection loop.
 */
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

    override suspend fun send(action: A) {
        val conn = currentConnection
            ?: throw IllegalStateException("Cannot send: not connected")
        conn.send(action)
    }

    /**
     * Establish the connection and automatically reconnect on failure.
     *
     * This method suspends until [disconnect] is called or all reconnection
     * attempts are exhausted. The initial connection counts as attempt 0.
     */
    override suspend fun connect() {
        stopped = false
        var attempt = 0

        while (!stopped && attempt < config.maxAttempts) {
            val conn = connectionFactory()
            currentConnection = conn

            if (attempt == 0) {
                _connectionState.value = ConnectionState.CONNECTING
            } else {
                _connectionState.value = ConnectionState.RECONNECTING
                val delay = config.delayForAttempt(attempt - 1)
                safeOnEvent(ReconnectionEvent.AttemptStarted(attempt, config.maxAttempts, delay))
                delay(delay)
                if (stopped) break
            }

            try {
                supervisorScope {
                    // Forward incoming actions from inner connection to shared channel
                    val forwardJob: Job = launch {
                        conn.incoming.collect { action ->
                            try {
                                incomingChannel.send(action)
                            } catch (_: ClosedSendChannelException) {
                                // disconnect() closed the shared channel — stop forwarding
                            }
                        }
                    }

                    // Monitor inner connection state
                    val stateJob: Job = launch {
                        conn.connectionState.collect { state ->
                            when (state) {
                                ConnectionState.CONNECTED -> {
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
                                    // Inner connection shouldn't emit this, but forward it
                                    _connectionState.value = ConnectionState.RECONNECTING
                                }
                            }
                        }
                    }

                    try {
                        conn.connect()
                    } finally {
                        // conn.connect() returned — connection terminated.
                        // Cancel state/forward jobs since the inner connection is done.
                        stateJob.cancel()
                        forwardJob.cancel()
                    }
                }

                // connect() returned normally — connection closed cleanly
                currentConnection = null
                if (!stopped) {
                    // Connection dropped without disconnect() — reconnect
                    attempt++
                    continue
                }
                break
            } catch (e: CancellationException) {
                currentConnection = null
                throw e
            } catch (e: Exception) {
                currentConnection = null
                if (stopped) break
                safeOnEvent(ReconnectionEvent.AttemptFailed(attempt, config.maxAttempts, e))
                attempt++
            }
        }

        if (!stopped && attempt >= config.maxAttempts) {
            _connectionState.value = ConnectionState.DISCONNECTED
            safeOnEvent(ReconnectionEvent.RetriesExhausted(config.maxAttempts, null))
        }
    }

    override suspend fun disconnect() {
        stopped = true
        _connectionState.value = ConnectionState.DISCONNECTED
        currentConnection?.disconnect()
        currentConnection = null
    }

    private fun safeOnEvent(event: ReconnectionEvent) {
        try {
            onEvent?.invoke(event)
        } catch (_: Exception) {
            // Swallow callback errors to avoid disrupting the reconnection loop.
        }
    }
}
