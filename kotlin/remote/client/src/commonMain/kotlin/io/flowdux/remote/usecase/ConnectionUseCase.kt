package io.flowdux.remote.usecase

import io.flowdux.Action
import io.flowdux.remote.ConnectionState
import io.flowdux.remote.TypedClientConnection
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Use case for managing client-side connection lifecycle, including reconnection and health checks.
 *
 * This abstraction separates connection business logic from the middleware,
 * making both easier to test and maintain.
 *
 * Typical usage:
 * ```kotlin
 * val connectionUseCase = ConnectionUseCaseImpl(
 *     connection = typedConnection,
 *     config = ConnectionConfig(
 *         reconnectMaxAttempts = 10,
 *         healthCheckInterval = 30.seconds,
 *     ),
 * )
 *
 * // In middleware processor:
 * on<Connect> { _, _ ->
 *     connectionUseCase.connect()
 * }
 * ```
 */
interface ConnectionUseCase {
    /**
     * Current connection state as a reactive flow.
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Incoming actions from the server, already decoded.
     */
    val incoming: Flow<Action>

    /**
     * Attempt to establish a connection.
     *
     * @return [ConnectionResult.Success] if connected, [ConnectionResult.Failure] otherwise.
     */
    suspend fun connect(): ConnectionResult

    /**
     * Disconnect from the server.
     */
    suspend fun disconnect()

    /**
     * Send an action to the server.
     *
     * @param action The action to send.
     */
    suspend fun send(action: Action)

    /**
     * Attempt to reconnect with exponential backoff according to the configuration.
     *
     * This is a cold flow that emits [ReconnectState] updates as reconnection progresses.
     * The flow completes when reconnection succeeds or all attempts are exhausted.
     *
     * @return A flow of [ReconnectState] updates.
     */
    fun reconnect(): Flow<ReconnectState>

    /**
     * Monitor connection state changes.
     *
     * Emits [ConnectionEvent] whenever the connection state changes or
     * when connection is unexpectedly lost/restored.
     *
     * @return A flow of [ConnectionEvent] updates.
     */
    fun monitorState(): Flow<ConnectionEvent>

    /**
     * Start periodic health checks.
     *
     * This is a cold flow that emits [HealthCheckResult] at regular intervals.
     * The flow continues until cancelled.
     *
     * Note: Health check implementation requires the server to respond to ping actions.
     * This base implementation provides the timing infrastructure; actual ping/pong
     * logic should be implemented in a subclass or via action dispatch.
     *
     * @param sendPing Function to send a ping and wait for pong response.
     * @return A flow of [HealthCheckResult] for each health check.
     */
    fun startHealthCheck(sendPing: suspend () -> Unit): Flow<HealthCheckResult>
}

/**
 * Default implementation of [ConnectionUseCase].
 *
 * Wraps a [TypedClientConnection] and adds reconnection and health check logic.
 *
 * @param A The action type.
 * @param connection The underlying typed connection.
 * @param config Configuration for reconnection and health checks.
 * @param random Random source for jitter calculation (injectable for testing).
 */
class ConnectionUseCaseImpl<A : Action>(
    private val connection: TypedClientConnection<A>,
    private val config: ConnectionConfig = ConnectionConfig(),
    private val random: Random = Random.Default,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ConnectionUseCase {

    private val _reconnectState = MutableStateFlow<ReconnectState>(ReconnectState.Idle)
    val reconnectState: StateFlow<ReconnectState> = _reconnectState.asStateFlow()

    override val connectionState: StateFlow<ConnectionState>
        get() = connection.connectionState

    @Suppress("UNCHECKED_CAST")
    override val incoming: Flow<Action>
        get() = connection.incoming.map { it as Action }

    override suspend fun connect(): ConnectionResult {
        return try {
            connection.connect()
            ConnectionResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionResult.Failure(e)
        }
    }

    override suspend fun disconnect() {
        connection.disconnect()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun send(action: Action) {
        connection.send(action as A)
    }

    override fun reconnect(): Flow<ReconnectState> = flow {
        var attempt = 0
        var currentDelay = config.reconnectInitialDelay
        var lastError: Throwable? = null

        _reconnectState.value = ReconnectState.Idle
        emit(ReconnectState.Idle)

        while (config.reconnectMaxAttempts == 0 || attempt < config.reconnectMaxAttempts) {
            attempt++

            // Calculate delay with jitter
            val jitteredDelay = calculateJitteredDelay(currentDelay)

            val attemptState = ReconnectState.Attempting(
                attempt = attempt,
                maxAttempts = config.reconnectMaxAttempts,
                delayMs = jitteredDelay.inWholeMilliseconds,
            )
            _reconnectState.value = attemptState
            emit(attemptState)

            // Wait before attempting
            delay(jitteredDelay)

            // Try to connect
            when (val result = connect()) {
                is ConnectionResult.Success -> {
                    val connected = ReconnectState.Connected
                    _reconnectState.value = connected
                    emit(connected)
                    return@flow
                }
                is ConnectionResult.Failure -> {
                    lastError = result.error
                }
            }

            // Increase delay for next attempt (exponential backoff)
            currentDelay = (currentDelay * config.reconnectMultiplier).coerceAtMost(config.reconnectMaxDelay)
        }

        // All attempts exhausted
        val failed = ReconnectState.Failed(
            lastError = lastError ?: RuntimeException("Connection failed after $attempt attempts"),
            attempts = attempt,
        )
        _reconnectState.value = failed
        emit(failed)
    }

    override fun monitorState(): Flow<ConnectionEvent> = flow {
        var previousState = connectionState.value
        var wasConnected = previousState == ConnectionState.CONNECTED

        connectionState.collect { currentState ->
            if (currentState != previousState) {
                emit(ConnectionEvent.StateChanged(from = previousState, to = currentState))

                // Detect connection loss
                if (wasConnected && currentState == ConnectionState.DISCONNECTED) {
                    emit(ConnectionEvent.ConnectionLost)
                }

                // Detect connection restored
                val isConnected = currentState == ConnectionState.CONNECTED
                if (!wasConnected && isConnected) {
                    emit(ConnectionEvent.ConnectionRestored)
                }

                previousState = currentState
                wasConnected = isConnected
            }
        }
    }

    override fun startHealthCheck(sendPing: suspend () -> Unit): Flow<HealthCheckResult> = flow {
        while (true) {
            delay(config.healthCheckInterval)

            // Only check if connected
            if (connectionState.value != ConnectionState.CONNECTED) {
                continue
            }

            val startTime = timeSource.markNow()

            val result = try {
                withTimeoutOrNull(config.healthCheckTimeout) {
                    sendPing()
                    val elapsed = startTime.elapsedNow()
                    HealthCheckResult.Healthy(elapsed.inWholeMilliseconds)
                } ?: HealthCheckResult.Timeout(config.healthCheckTimeout.inWholeMilliseconds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HealthCheckResult.Error(e)
            }

            emit(result)
        }
    }

    /**
     * Calculate delay with jitter applied.
     */
    private fun calculateJitteredDelay(baseDelay: Duration): Duration {
        if (config.reconnectJitter <= 0.0) return baseDelay

        val jitterRange = baseDelay.inWholeMilliseconds * config.reconnectJitter
        val jitter = (random.nextDouble() * 2 - 1) * jitterRange // Range: -jitterRange to +jitterRange
        val delayMs = (baseDelay.inWholeMilliseconds + jitter).toLong().coerceAtLeast(0)

        return delayMs.milliseconds
    }
}

/**
 * Extension function to start reconnection in a coroutine scope.
 *
 * @param scope The coroutine scope to launch in.
 * @param onStateChange Callback for each state change.
 * @return The job that can be cancelled to stop reconnection.
 */
fun ConnectionUseCase.startReconnectionJob(
    scope: CoroutineScope,
    onStateChange: suspend (ReconnectState) -> Unit,
): Job = scope.launch {
    reconnect().collect { state ->
        onStateChange(state)
    }
}

/**
 * Extension function to start health check monitoring in a coroutine scope.
 *
 * @param scope The coroutine scope to launch in.
 * @param sendPing Function to send a ping and wait for response.
 * @param onResult Callback for each health check result.
 * @return The job that can be cancelled to stop health checks.
 */
fun ConnectionUseCase.startHealthCheckJob(
    scope: CoroutineScope,
    sendPing: suspend () -> Unit,
    onResult: suspend (HealthCheckResult) -> Unit,
): Job = scope.launch {
    startHealthCheck(sendPing).collect { result ->
        onResult(result)
    }
}

/**
 * Extension function to start connection state monitoring in a coroutine scope.
 *
 * @param scope The coroutine scope to launch in.
 * @param onEvent Callback for each connection event.
 * @return The job that can be cancelled to stop monitoring.
 */
fun ConnectionUseCase.startMonitoringJob(
    scope: CoroutineScope,
    onEvent: suspend (ConnectionEvent) -> Unit,
): Job = scope.launch {
    monitorState().collect { event ->
        onEvent(event)
    }
}
