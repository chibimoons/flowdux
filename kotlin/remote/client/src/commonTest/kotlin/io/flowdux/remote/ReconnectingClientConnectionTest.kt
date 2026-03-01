package io.flowdux.remote

import app.cash.turbine.test
import io.flowdux.Action
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReconnectingClientConnectionTest {

    // -- ReconnectionConfig tests --

    @Test
    fun configValidatesMaxAttempts() {
        assertFailsWith<IllegalArgumentException> {
            ReconnectionConfig(maxAttempts = 0)
        }
    }

    @Test
    fun configValidatesInitialDelay() {
        assertFailsWith<IllegalArgumentException> {
            ReconnectionConfig(initialDelay = 0.milliseconds)
        }
    }

    @Test
    fun configValidatesMaxDelayGreaterThanInitial() {
        assertFailsWith<IllegalArgumentException> {
            ReconnectionConfig(initialDelay = 10.seconds, maxDelay = 1.seconds)
        }
    }

    @Test
    fun configValidatesFactorMinimum() {
        assertFailsWith<IllegalArgumentException> {
            ReconnectionConfig(factor = 0.5)
        }
    }

    @Test
    fun configValidatesJitterRange() {
        assertFailsWith<IllegalArgumentException> {
            ReconnectionConfig(jitterFactor = 1.5)
        }
    }

    @Test
    fun delayForAttemptAppliesExponentialBackoff() {
        val config = ReconnectionConfig(
            initialDelay = 100.milliseconds,
            maxDelay = 10.seconds,
            factor = 2.0,
            jitterFactor = 0.0, // no jitter for deterministic test
        )
        assertEquals(100.milliseconds, config.delayForAttempt(0) { 0.0 })
        assertEquals(200.milliseconds, config.delayForAttempt(1) { 0.0 })
        assertEquals(400.milliseconds, config.delayForAttempt(2) { 0.0 })
        assertEquals(800.milliseconds, config.delayForAttempt(3) { 0.0 })
    }

    @Test
    fun delayForAttemptCapsAtMaxDelay() {
        val config = ReconnectionConfig(
            initialDelay = 1.seconds,
            maxDelay = 5.seconds,
            factor = 10.0,
            jitterFactor = 0.0,
        )
        // attempt 0 = 1s, attempt 1 = 10s → capped to 5s
        assertEquals(5.seconds, config.delayForAttempt(1) { 0.0 })
    }

    @Test
    fun delayForAttemptAppliesJitter() {
        val config = ReconnectionConfig(
            initialDelay = 1.seconds,
            maxDelay = 30.seconds,
            factor = 2.0,
            jitterFactor = 0.5,
        )
        // With random = 1.0: delay = 1000 - (1000 * 0.5 * 1.0) = 500ms
        assertEquals(500.milliseconds, config.delayForAttempt(0) { 1.0 })
        // With random = 0.0: delay = 1000 - 0 = 1000ms (no jitter)
        assertEquals(1.seconds, config.delayForAttempt(0) { 0.0 })
    }

    // -- ReconnectingClientConnection tests --

    @Test
    fun initialConnectionSucceeds() = runTest {
        val events = mutableListOf<ReconnectionEvent>()
        val innerConnection = SuspendingMockConnection<TestAction>()

        val conn = ReconnectingClientConnection(
            connectionFactory = { innerConnection },
            config = ReconnectionConfig(maxAttempts = 3, initialDelay = 10.milliseconds),
            onEvent = { events.add(it) },
        )

        conn.connectionState.test {
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())

            val connectJob = launch { conn.connect() }
            assertEquals(ConnectionState.CONNECTING, awaitItem())

            innerConnection.completeConnect()
            assertEquals(ConnectionState.CONNECTED, awaitItem())

            conn.disconnect()
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())

            connectJob.join()
            cancelAndIgnoreRemainingEvents()
        }

        val connected = events.filterIsInstance<ReconnectionEvent.Connected>()
        assertEquals(1, connected.size)
        assertEquals(0, connected[0].attempt)
    }

    @Test
    fun reconnectsAfterConnectionDrop() = runTest {
        val events = mutableListOf<ReconnectionEvent>()
        var connectCount = 0
        val connections = mutableListOf<SuspendingMockConnection<TestAction>>()

        val conn = ReconnectingClientConnection(
            connectionFactory = {
                SuspendingMockConnection<TestAction>().also {
                    connectCount++
                    connections.add(it)
                }
            },
            config = ReconnectionConfig(
                maxAttempts = 3,
                initialDelay = 10.milliseconds,
                maxDelay = 50.milliseconds,
                jitterFactor = 0.0,
            ),
            onEvent = { events.add(it) },
        )

        conn.connectionState.test {
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())

            val connectJob = launch { conn.connect() }

            // Initial connection
            assertEquals(ConnectionState.CONNECTING, awaitItem())
            connections[0].completeConnect()
            assertEquals(ConnectionState.CONNECTED, awaitItem())

            // Simulate connection drop
            connections[0].simulateDisconnect()
            assertEquals(ConnectionState.RECONNECTING, awaitItem())

            // Wait for backoff + new connection creation
            withTimeoutOrNull(2000) {
                while (connections.size < 2) {
                    delay(10)
                }
            }
            assertNotNull(connections.getOrNull(1), "Second connection should be created")
            connections[1].completeConnect()
            assertEquals(ConnectionState.CONNECTED, awaitItem())

            // Clean disconnect
            conn.disconnect()
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())

            connectJob.join()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(2, connectCount)
        assertTrue(events.any { it is ReconnectionEvent.AttemptStarted && it.attempt == 1 })
    }

    @Test
    fun forwardsIncomingActionsAcrossReconnections() = runTest {
        val connections = mutableListOf<SuspendingMockConnection<TestAction>>()

        val conn = ReconnectingClientConnection(
            connectionFactory = {
                SuspendingMockConnection<TestAction>().also { connections.add(it) }
            },
            config = ReconnectionConfig(
                maxAttempts = 3,
                initialDelay = 10.milliseconds,
                jitterFactor = 0.0,
            ),
        )

        conn.incoming.test {
            val connectJob = launch { conn.connect() }
            delay(50)

            // First connection
            connections[0].completeConnect()
            delay(50)
            connections[0].simulateServerAction(TestAction.Add(10))
            assertEquals(TestAction.Add(10), awaitItem())

            // Drop and reconnect
            connections[0].simulateDisconnect()
            // Wait for backoff + new connection creation
            withTimeoutOrNull(2000) {
                while (connections.size < 2) {
                    delay(10)
                }
            }
            assertNotNull(connections.getOrNull(1), "Second connection should be created")
            connections[1].completeConnect()
            delay(50)

            // Actions from new connection should still arrive
            connections[1].simulateServerAction(TestAction.Add(20))
            assertEquals(TestAction.Add(20), awaitItem())

            conn.disconnect()
            connectJob.join()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun retriesExhaustedWhenAllAttemptsFail() = runTest {
        val events = mutableListOf<ReconnectionEvent>()
        var connectCount = 0

        val conn = ReconnectingClientConnection(
            connectionFactory = {
                connectCount++
                FailingMockConnection(RuntimeException("fail $connectCount"))
            },
            config = ReconnectionConfig(
                maxAttempts = 3,
                initialDelay = 10.milliseconds,
                maxDelay = 50.milliseconds,
                jitterFactor = 0.0,
            ),
            onEvent = { events.add(it) },
        )

        conn.connect()

        assertEquals(3, connectCount)
        assertEquals(ConnectionState.DISCONNECTED, conn.connectionState.value)
        val exhausted = events.filterIsInstance<ReconnectionEvent.RetriesExhausted>()
        assertEquals(1, exhausted.size)
        assertNotNull(exhausted[0].lastCause, "lastCause should contain the last failure")
        assertEquals(3, events.filterIsInstance<ReconnectionEvent.AttemptFailed>().size)
    }

    @Test
    fun disconnectStopsReconnectionLoop() = runTest {
        val events = mutableListOf<ReconnectionEvent>()
        val connections = mutableListOf<SuspendingMockConnection<TestAction>>()

        val conn = ReconnectingClientConnection(
            connectionFactory = {
                SuspendingMockConnection<TestAction>().also { connections.add(it) }
            },
            config = ReconnectionConfig(
                maxAttempts = 10,
                initialDelay = 10.milliseconds,
                jitterFactor = 0.0,
            ),
            onEvent = { events.add(it) },
        )

        val connectJob = launch { conn.connect() }

        // Connect and verify
        delay(50)
        connections[0].completeConnect()
        delay(50)
        assertEquals(ConnectionState.CONNECTED, conn.connectionState.value)

        // Disconnect mid-connection
        conn.disconnect()
        connectJob.join()

        assertEquals(ConnectionState.DISCONNECTED, conn.connectionState.value)
        // Should NOT have exhausted retries
        assertTrue(events.none { it is ReconnectionEvent.RetriesExhausted })
    }

    @Test
    fun sendDelegatestoCurrentConnection() = runTest {
        val connections = mutableListOf<SuspendingMockConnection<TestAction>>()

        val conn = ReconnectingClientConnection(
            connectionFactory = {
                SuspendingMockConnection<TestAction>().also { connections.add(it) }
            },
            config = ReconnectionConfig(maxAttempts = 3, initialDelay = 10.milliseconds),
        )

        val connectJob = launch { conn.connect() }

        delay(50)
        connections[0].completeConnect()
        delay(50)

        conn.send(TestAction.Add(42))
        assertEquals(1, connections[0].sentActions.size)
        assertEquals(TestAction.Add(42), connections[0].sentActions[0])

        conn.disconnect()
        connectJob.join()
    }

    @Test
    fun sendThrowsWhenNotConnected() = runTest {
        val conn = ReconnectingClientConnection<TestAction>(
            connectionFactory = { SuspendingMockConnection() },
            config = ReconnectionConfig(maxAttempts = 3, initialDelay = 10.milliseconds),
        )

        assertFailsWith<IllegalStateException> {
            conn.send(TestAction.Add(1))
        }
    }

    @Test
    fun eventCallbackExceptionsAreSwallowed() = runTest {
        val conn = ReconnectingClientConnection(
            connectionFactory = { FailingMockConnection<TestAction>(RuntimeException("fail")) },
            config = ReconnectionConfig(
                maxAttempts = 2,
                initialDelay = 10.milliseconds,
                jitterFactor = 0.0,
            ),
            onEvent = { throw RuntimeException("callback error") },
        )

        // Should not throw despite callback errors
        conn.connect()
        assertEquals(ConnectionState.DISCONNECTED, conn.connectionState.value)
    }

    @Test
    fun firstConnectionFailureTriggersReconnect() = runTest {
        val events = mutableListOf<ReconnectionEvent>()
        var callCount = 0
        val connections = mutableListOf<SuspendingMockConnection<TestAction>>()

        val conn = ReconnectingClientConnection(
            connectionFactory = {
                callCount++
                if (callCount == 1) {
                    FailingMockConnection(RuntimeException("initial failure"))
                } else {
                    SuspendingMockConnection<TestAction>().also { connections.add(it) }
                }
            },
            config = ReconnectionConfig(
                maxAttempts = 3,
                initialDelay = 10.milliseconds,
                jitterFactor = 0.0,
            ),
            onEvent = { events.add(it) },
        )

        val connectJob = launch { conn.connect() }

        // Wait for first failure + backoff + second connection
        delay(100)
        assertNotNull(connections.firstOrNull(), "Second connection should have been created")
        connections[0].completeConnect()
        delay(50)

        assertEquals(ConnectionState.CONNECTED, conn.connectionState.value)
        assertTrue(events.any { it is ReconnectionEvent.AttemptFailed && it.attempt == 0 })
        assertTrue(events.any { it is ReconnectionEvent.Connected && it.attempt == 1 })

        conn.disconnect()
        connectJob.join()
    }

    @Test
    fun resetAttemptsAfterSuccessfulConnection() = runTest {
        val events = mutableListOf<ReconnectionEvent>()
        val connections = mutableListOf<SuspendingMockConnection<TestAction>>()

        val conn = ReconnectingClientConnection(
            connectionFactory = {
                SuspendingMockConnection<TestAction>().also { connections.add(it) }
            },
            config = ReconnectionConfig(
                maxAttempts = 2,
                initialDelay = 10.milliseconds,
                jitterFactor = 0.0,
            ),
            onEvent = { events.add(it) },
        )

        val connectJob = launch { conn.connect() }

        // First connection succeeds
        delay(50)
        connections[0].completeConnect()
        delay(50)
        assertEquals(ConnectionState.CONNECTED, conn.connectionState.value)

        // Drop connection — attempt counter resets to 1 after successful connection
        connections[0].simulateDisconnect()
        withTimeoutOrNull(2000) {
            while (connections.size < 2) {
                delay(10)
            }
        }
        assertNotNull(connections.getOrNull(1), "Second connection should be created")
        connections[1].completeConnect()
        delay(50)
        assertEquals(ConnectionState.CONNECTED, conn.connectionState.value)

        // Drop again — attempt counter resets again since connection was successful
        // Without reset, maxAttempts=2 would be exhausted here
        connections[1].simulateDisconnect()
        withTimeoutOrNull(2000) {
            while (connections.size < 3) {
                delay(10)
            }
        }
        assertNotNull(connections.getOrNull(2), "Third connection should be created (attempt reset)")
        connections[2].completeConnect()
        delay(50)
        assertEquals(ConnectionState.CONNECTED, conn.connectionState.value)

        conn.disconnect()
        connectJob.join()

        // Should NOT have exhausted retries since counter resets each time
        assertTrue(events.none { it is ReconnectionEvent.RetriesExhausted })
        assertEquals(3, connections.size)
    }

    // -- Test helpers --

    /**
     * Mock connection that suspends in [connect] until explicitly completed.
     * Supports simulating connection drops and server actions.
     */
    private class SuspendingMockConnection<A : Action> : TypedClientConnection<A> {
        private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        private val incomingChannel = Channel<A>(Channel.BUFFERED)
        override val incoming: Flow<A> = incomingChannel.receiveAsFlow()

        val sentActions = mutableListOf<A>()

        private val connectSignal = CompletableDeferred<Unit>()
        private val disconnectSignal = CompletableDeferred<Unit>()

        override suspend fun send(action: A) {
            sentActions.add(action)
        }

        override suspend fun connect() {
            _connectionState.value = ConnectionState.CONNECTING
            connectSignal.await()
            _connectionState.value = ConnectionState.CONNECTED
            try {
                disconnectSignal.await()
            } finally {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }

        override suspend fun disconnect() {
            _connectionState.value = ConnectionState.DISCONNECTED
            disconnectSignal.complete(Unit)
        }

        fun completeConnect() {
            connectSignal.complete(Unit)
        }

        fun simulateDisconnect() {
            disconnectSignal.complete(Unit)
        }

        suspend fun simulateServerAction(action: A) {
            incomingChannel.send(action)
        }
    }

    /**
     * Mock connection that always throws from [connect].
     */
    private class FailingMockConnection<A : Action>(private val exception: Exception) : TypedClientConnection<A> {
        private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        override val incoming: Flow<A> = Channel<A>().receiveAsFlow()

        override suspend fun send(action: A): Unit = throw IllegalStateException("Not connected")

        override suspend fun connect(): Unit = throw exception

        override suspend fun disconnect() {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }
}
