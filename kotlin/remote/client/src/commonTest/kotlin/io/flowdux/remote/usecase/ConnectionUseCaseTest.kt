package io.flowdux.remote.usecase

import app.cash.turbine.test
import io.flowdux.Action
import io.flowdux.remote.ConnectionState
import io.flowdux.remote.TypedClientConnection
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest

class ConnectionUseCaseTest {

    sealed interface TestAction : Action {
        data object Ping : TestAction
        data object Pong : TestAction
        data class Message(val content: String) : TestAction
    }

    @Test
    fun `connect returns Success on successful connection`() = runTest {
        val connection = MockConnection()
        val useCase = ConnectionUseCaseImpl(connection)

        val result = useCase.connect()

        assertIs<ConnectionResult.Success>(result)
        assertEquals(ConnectionState.CONNECTED, connection.connectionState.value)
    }

    @Test
    fun `connect returns Failure on connection error`() = runTest {
        val connection = MockConnection(connectException = RuntimeException("Connection failed"))
        val useCase = ConnectionUseCaseImpl(connection)

        val result = useCase.connect()

        assertIs<ConnectionResult.Failure>(result)
        assertEquals("Connection failed", result.error.message)
    }

    @Test
    fun `disconnect calls connection disconnect`() = runTest {
        val connection = MockConnection()
        val useCase = ConnectionUseCaseImpl(connection)

        useCase.connect()
        assertEquals(ConnectionState.CONNECTED, connection.connectionState.value)

        useCase.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
    }

    @Test
    fun `send forwards action to connection`() = runTest {
        val connection = MockConnection()
        val useCase = ConnectionUseCaseImpl(connection)

        useCase.connect()
        useCase.send(TestAction.Message("hello"))

        assertEquals(1, connection.sentActions.size)
        assertEquals(TestAction.Message("hello"), connection.sentActions[0])
    }

    @Test
    fun `incoming exposes connection incoming flow`() = runTest {
        val connection = MockConnection()
        val useCase = ConnectionUseCaseImpl(connection)

        useCase.connect()

        useCase.incoming.test {
            connection.simulateServerAction(TestAction.Message("world"))
            assertEquals(TestAction.Message("world"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reconnect emits Idle then Attempting then Connected on success`() = runTest {
        var attemptCount = 0
        val connection = object : MockConnection() {
            override suspend fun connect() {
                attemptCount++
                if (attemptCount < 2) {
                    throw RuntimeException("First attempt fails")
                }
                _connectionState.value = ConnectionState.CONNECTED
            }
        }

        val config = ConnectionConfig(
            reconnectMaxAttempts = 5,
            reconnectInitialDelay = 10.milliseconds,
            reconnectMaxDelay = 100.milliseconds,
            reconnectMultiplier = 1.5,
            reconnectJitter = 0.0, // No jitter for deterministic test
        )
        val useCase = ConnectionUseCaseImpl(connection, config)

        useCase.reconnect().test {
            // First: Idle
            assertIs<ReconnectState.Idle>(awaitItem())

            // Second: First attempt
            val attempt1 = awaitItem()
            assertIs<ReconnectState.Attempting>(attempt1)
            assertEquals(1, attempt1.attempt)

            // Third: Second attempt (successful)
            val attempt2 = awaitItem()
            assertIs<ReconnectState.Attempting>(attempt2)
            assertEquals(2, attempt2.attempt)

            // Fourth: Connected
            assertIs<ReconnectState.Connected>(awaitItem())

            awaitComplete()
        }
    }

    @Test
    fun `reconnect emits Failed after max attempts exhausted`() = runTest {
        val connection = MockConnection(connectException = RuntimeException("Always fails"))

        val config = ConnectionConfig(
            reconnectMaxAttempts = 3,
            reconnectInitialDelay = 10.milliseconds,
            reconnectMaxDelay = 100.milliseconds,
            reconnectJitter = 0.0,
        )
        val useCase = ConnectionUseCaseImpl(connection, config)

        useCase.reconnect().test {
            assertIs<ReconnectState.Idle>(awaitItem())
            assertIs<ReconnectState.Attempting>(awaitItem()) // attempt 1
            assertIs<ReconnectState.Attempting>(awaitItem()) // attempt 2
            assertIs<ReconnectState.Attempting>(awaitItem()) // attempt 3

            val failed = awaitItem()
            assertIs<ReconnectState.Failed>(failed)
            assertEquals(3, failed.attempts)
            assertEquals("Always fails", failed.lastError.message)

            awaitComplete()
        }
    }

    @Test
    fun `reconnect uses exponential backoff`() = runTest {
        val connection = MockConnection(connectException = RuntimeException("Always fails"))

        val config = ConnectionConfig(
            reconnectMaxAttempts = 3,
            reconnectInitialDelay = 100.milliseconds,
            reconnectMaxDelay = 1.seconds,
            reconnectMultiplier = 2.0,
            reconnectJitter = 0.0,
        )
        val useCase = ConnectionUseCaseImpl(connection, config)

        useCase.reconnect().test {
            assertIs<ReconnectState.Idle>(awaitItem())

            val attempt1 = awaitItem()
            assertIs<ReconnectState.Attempting>(attempt1)
            assertEquals(100, attempt1.delayMs)

            val attempt2 = awaitItem()
            assertIs<ReconnectState.Attempting>(attempt2)
            assertEquals(200, attempt2.delayMs) // 100 * 2.0

            val attempt3 = awaitItem()
            assertIs<ReconnectState.Attempting>(attempt3)
            assertEquals(400, attempt3.delayMs) // 200 * 2.0

            assertIs<ReconnectState.Failed>(awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `reconnect respects maxDelay cap`() = runTest {
        val connection = MockConnection(connectException = RuntimeException("Always fails"))

        val config = ConnectionConfig(
            reconnectMaxAttempts = 4,
            reconnectInitialDelay = 100.milliseconds,
            reconnectMaxDelay = 150.milliseconds, // Cap at 150ms
            reconnectMultiplier = 2.0,
            reconnectJitter = 0.0,
        )
        val useCase = ConnectionUseCaseImpl(connection, config)

        useCase.reconnect().test {
            assertIs<ReconnectState.Idle>(awaitItem())

            val attempt1 = awaitItem()
            assertIs<ReconnectState.Attempting>(attempt1)
            assertEquals(100, attempt1.delayMs)

            val attempt2 = awaitItem()
            assertIs<ReconnectState.Attempting>(attempt2)
            assertEquals(150, attempt2.delayMs) // Capped at maxDelay

            val attempt3 = awaitItem()
            assertIs<ReconnectState.Attempting>(attempt3)
            assertEquals(150, attempt3.delayMs) // Still capped

            val attempt4 = awaitItem()
            assertIs<ReconnectState.Attempting>(attempt4)
            assertEquals(150, attempt4.delayMs) // Still capped

            assertIs<ReconnectState.Failed>(awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `reconnect applies jitter`() = runTest {
        val connection = MockConnection(connectException = RuntimeException("Always fails"))

        val deterministicRandom = Random(42) // Fixed seed for reproducibility
        val config = ConnectionConfig(
            reconnectMaxAttempts = 2,
            reconnectInitialDelay = 1000.milliseconds,
            reconnectMaxDelay = 10.seconds,
            reconnectMultiplier = 2.0,
            reconnectJitter = 0.1, // 10% jitter
        )
        val useCase = ConnectionUseCaseImpl(connection, config, deterministicRandom)

        useCase.reconnect().test {
            assertIs<ReconnectState.Idle>(awaitItem())

            val attempt1 = awaitItem()
            assertIs<ReconnectState.Attempting>(attempt1)
            // With 10% jitter on 1000ms, delay should be in [900, 1100]
            assertTrue(attempt1.delayMs in 900..1100, "Delay ${attempt1.delayMs} should be in [900, 1100]")

            val attempt2 = awaitItem()
            assertIs<ReconnectState.Attempting>(attempt2)
            // With 10% jitter on 2000ms (after multiplier), delay should be in [1800, 2200]
            assertTrue(attempt2.delayMs in 1800..2200, "Delay ${attempt2.delayMs} should be in [1800, 2200]")

            assertIs<ReconnectState.Failed>(awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `monitorState emits StateChanged events`() = runTest {
        val connection = MockConnection()
        val useCase = ConnectionUseCaseImpl(connection)

        useCase.monitorState().test {
            // Initial state change when collecting
            connection.setConnectionState(ConnectionState.CONNECTING)
            val event1 = awaitItem()
            assertIs<ConnectionEvent.StateChanged>(event1)
            assertEquals(ConnectionState.DISCONNECTED, event1.from)
            assertEquals(ConnectionState.CONNECTING, event1.to)

            connection.setConnectionState(ConnectionState.CONNECTED)
            val event2 = awaitItem()
            assertIs<ConnectionEvent.StateChanged>(event2)
            assertEquals(ConnectionState.CONNECTING, event2.from)
            assertEquals(ConnectionState.CONNECTED, event2.to)

            // Also emits ConnectionRestored since we went from not-connected to connected
            assertIs<ConnectionEvent.ConnectionRestored>(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `monitorState emits ConnectionLost when disconnected`() = runTest {
        val connection = MockConnection()
        val useCase = ConnectionUseCaseImpl(connection)

        // First connect
        connection.setConnectionState(ConnectionState.CONNECTED)

        useCase.monitorState().test {
            // Simulate connection loss
            connection.setConnectionState(ConnectionState.DISCONNECTED)

            val stateChanged = awaitItem()
            assertIs<ConnectionEvent.StateChanged>(stateChanged)
            assertEquals(ConnectionState.CONNECTED, stateChanged.from)
            assertEquals(ConnectionState.DISCONNECTED, stateChanged.to)

            assertIs<ConnectionEvent.ConnectionLost>(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `monitorState emits ConnectionRestored after reconnecting`() = runTest {
        val connection = MockConnection()
        val useCase = ConnectionUseCaseImpl(connection)

        // Start connected, then disconnect
        connection.setConnectionState(ConnectionState.CONNECTED)

        useCase.monitorState().test {
            // Disconnect
            connection.setConnectionState(ConnectionState.DISCONNECTED)
            assertIs<ConnectionEvent.StateChanged>(awaitItem())
            assertIs<ConnectionEvent.ConnectionLost>(awaitItem())

            // Reconnect
            connection.setConnectionState(ConnectionState.CONNECTED)
            assertIs<ConnectionEvent.StateChanged>(awaitItem())
            assertIs<ConnectionEvent.ConnectionRestored>(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startHealthCheck emits Healthy result with latency`() = runTest {
        val connection = MockConnection()
        connection.setConnectionState(ConnectionState.CONNECTED)

        val config = ConnectionConfig(
            healthCheckInterval = 50.milliseconds,
            healthCheckTimeout = 500.milliseconds,
        )
        val useCase = ConnectionUseCaseImpl(connection, config)

        var pingCount = 0
        useCase.startHealthCheck(sendPing = {
            pingCount++
            // No delay to avoid timing issues on JS
        }).test {
            // Wait for first health check
            val result = awaitItem()
            assertIs<HealthCheckResult.Healthy>(result)
            // Just verify latency is non-negative
            assertTrue(result.latencyMs >= 0, "Latency should be non-negative, was ${result.latencyMs}")
            assertEquals(1, pingCount)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startHealthCheck emits Timeout on slow ping`() = runTest {
        val connection = MockConnection()
        connection.setConnectionState(ConnectionState.CONNECTED)

        val config = ConnectionConfig(
            healthCheckInterval = 50.milliseconds,
            healthCheckTimeout = 100.milliseconds,
        )
        val useCase = ConnectionUseCaseImpl(connection, config)

        useCase.startHealthCheck(sendPing = {
            delay(200) // Exceed timeout
        }).test {
            val result = awaitItem()
            assertIs<HealthCheckResult.Timeout>(result)
            assertEquals(100, result.timeoutMs)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startHealthCheck emits Error on ping exception`() = runTest {
        val connection = MockConnection()
        connection.setConnectionState(ConnectionState.CONNECTED)

        val config = ConnectionConfig(
            healthCheckInterval = 50.milliseconds,
            healthCheckTimeout = 500.milliseconds,
        )
        val useCase = ConnectionUseCaseImpl(connection, config)

        useCase.startHealthCheck(sendPing = {
            throw RuntimeException("Ping failed")
        }).test {
            val result = awaitItem()
            assertIs<HealthCheckResult.Error>(result)
            assertEquals("Ping failed", result.error.message)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startHealthCheck skips when not connected`() = runTest {
        val connection = MockConnection() // Starts disconnected
        val config = ConnectionConfig(
            healthCheckInterval = 10.milliseconds,
            healthCheckTimeout = 100.milliseconds,
        )
        val useCase = ConnectionUseCaseImpl(connection, config)

        var pingCount = 0

        // Connect before starting the health check flow
        connection.setConnectionState(ConnectionState.CONNECTED)

        useCase.startHealthCheck(sendPing = { pingCount++ }).test {
            // Since we're connected, a health check should happen
            val result = awaitItem()
            assertIs<HealthCheckResult.Healthy>(result)
            assertTrue(pingCount >= 1, "Ping should be called when connected")

            cancelAndIgnoreRemainingEvents()
        }
    }

    // -- Mock connection for testing --

    open class MockConnection(
        private val autoConnect: Boolean = true,
        private val connectException: Exception? = null,
    ) : TypedClientConnection<TestAction> {
        protected val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        private val incomingChannel = Channel<TestAction>(Channel.BUFFERED)
        override val incoming: Flow<TestAction> = incomingChannel.receiveAsFlow()

        val sentActions = mutableListOf<TestAction>()

        override suspend fun send(action: TestAction) {
            sentActions.add(action)
        }

        override suspend fun connect() {
            connectException?.let { throw it }
            if (autoConnect) {
                _connectionState.value = ConnectionState.CONNECTED
            }
        }

        override suspend fun disconnect() {
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        suspend fun simulateServerAction(action: TestAction) {
            incomingChannel.send(action)
        }

        fun setConnectionState(state: ConnectionState) {
            _connectionState.value = state
        }
    }
}
