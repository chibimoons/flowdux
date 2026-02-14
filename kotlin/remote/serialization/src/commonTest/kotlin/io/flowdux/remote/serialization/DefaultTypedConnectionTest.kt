package io.flowdux.remote.serialization

import io.flowdux.Action
import io.flowdux.remote.ActionCodec
import io.flowdux.remote.ClientConnection
import io.flowdux.remote.ConnectionState
import io.flowdux.remote.DefaultTypedClientConnection
import io.flowdux.remote.MessageCodec
import io.flowdux.remote.ServerResponse
import io.flowdux.remote.server.DefaultTypedServerConnection
import io.flowdux.remote.server.connection.ServerConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultTypedConnectionTest {

    @Serializable
    sealed interface TestAction : Action {
        @Serializable data class Ping(val id: Int) : TestAction
    }

    // -- Mock ClientConnection --

    private class MockClientConnection : ClientConnection {
        private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        override val connectionState: StateFlow<ConnectionState> = _connectionState
        private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
        override val incoming: Flow<String> = _incoming
        override suspend fun send(message: String) {}
        override suspend fun connect() {}
        override suspend fun disconnect() {}

        fun emit(raw: String) { _incoming.tryEmit(raw) }
    }

    // -- Mock ServerConnection --

    private class MockServerConnection : ServerConnection {
        override val isActive: Boolean = true
        private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
        override val incoming: Flow<String> = _incoming
        override suspend fun send(message: String) {}

        fun emit(raw: String) { _incoming.tryEmit(raw) }
    }

    // -- Failing codecs --

    private class FailingActionCodec : ActionCodec<TestAction> {
        override fun encode(action: TestAction): String = throw RuntimeException("encode failed")
        override fun decode(json: String): TestAction = throw RuntimeException("decode failed")
    }

    private class FailingMessageCodec : MessageCodec {
        override fun encodeActionMessage(actionJson: String) = ""
        override fun decodeActionFromClient(raw: String): String = throw RuntimeException("message decode failed")
        override fun encodeServerResponse(actions: List<String>) = ""
        override fun decodeServerMessage(raw: String): ServerResponse = throw RuntimeException("server message decode failed")
    }

    private val goodActionCodec = actionCodecOf<TestAction>()
    private val goodMessageCodec = JsonMessageCodec()

    // ==================== Client onDecodeError Tests ====================

    @Test
    fun `client - onDecodeError called when server message decode fails`() = runTest {
        val errors = mutableListOf<Exception>()
        val mockConn = MockClientConnection()

        val typed = DefaultTypedClientConnection(
            connection = mockConn,
            actionCodec = goodActionCodec,
            messageCodec = FailingMessageCodec(),
            onDecodeError = { errors.add(it) },
        )

        val received = mutableListOf<TestAction>()
        val job = launch { typed.incoming.collect { received.add(it) } }
        yield() // Let collector start

        mockConn.emit("bad message")
        yield() // Let collector process

        assertEquals(1, errors.size)
        assertIs<RuntimeException>(errors[0])
        assertTrue(received.isEmpty())

        job.cancel()
    }

    @Test
    fun `client - onDecodeError called when action decode fails`() = runTest {
        val errors = mutableListOf<Exception>()
        val mockConn = MockClientConnection()

        val typed = DefaultTypedClientConnection(
            connection = mockConn,
            actionCodec = FailingActionCodec(),
            messageCodec = goodMessageCodec,
            onDecodeError = { errors.add(it) },
        )

        val received = mutableListOf<TestAction>()
        val job = launch { typed.incoming.collect { received.add(it) } }
        yield()

        // Valid server response wrapping an action
        val serverMsg = goodMessageCodec.encodeServerResponse(listOf("""{"type":"io.flowdux.remote.serialization.DefaultTypedConnectionTest.TestAction.Ping","id":1}"""))
        mockConn.emit(serverMsg)
        yield()

        assertEquals(1, errors.size)
        assertTrue(received.isEmpty())

        job.cancel()
    }

    @Test
    fun `client - null onDecodeError does not throw`() = runTest {
        val mockConn = MockClientConnection()

        val typed = DefaultTypedClientConnection(
            connection = mockConn,
            actionCodec = goodActionCodec,
            messageCodec = FailingMessageCodec(),
            onDecodeError = null,
        )

        val received = mutableListOf<TestAction>()
        val job = launch { typed.incoming.collect { received.add(it) } }
        yield()

        mockConn.emit("bad message")
        yield()

        // No crash, message silently skipped
        assertTrue(received.isEmpty())

        job.cancel()
    }

    @Test
    fun `client - processing continues after decode error`() = runTest {
        val errors = mutableListOf<Exception>()
        val mockConn = MockClientConnection()

        val typed = DefaultTypedClientConnection(
            connection = mockConn,
            actionCodec = goodActionCodec,
            messageCodec = goodMessageCodec,
            onDecodeError = { errors.add(it) },
        )

        val received = mutableListOf<TestAction>()
        val job = launch { typed.incoming.collect { received.add(it) } }
        yield()

        // Bad message
        mockConn.emit("not valid json response")
        yield()

        // Good message
        val actionJson = goodActionCodec.encode(TestAction.Ping(42))
        val goodMsg = goodMessageCodec.encodeServerResponse(listOf(actionJson))
        mockConn.emit(goodMsg)
        yield()

        assertEquals(1, errors.size)
        assertEquals(1, received.size)
        assertEquals(TestAction.Ping(42), received[0])

        job.cancel()
    }

    // ==================== Server onDecodeError Tests ====================

    @Test
    fun `server - onDecodeError called when client message decode fails`() = runTest {
        val errors = mutableListOf<Exception>()
        val mockConn = MockServerConnection()

        val typed = DefaultTypedServerConnection(
            connection = mockConn,
            actionCodec = goodActionCodec,
            messageCodec = FailingMessageCodec(),
            onDecodeError = { errors.add(it) },
        )

        val received = mutableListOf<TestAction>()
        val job = launch { typed.incoming.collect { received.add(it) } }
        yield()

        mockConn.emit("bad message")
        yield()

        assertEquals(1, errors.size)
        assertIs<RuntimeException>(errors[0])
        assertTrue(received.isEmpty())

        job.cancel()
    }

    @Test
    fun `server - onDecodeError called when action decode fails`() = runTest {
        val errors = mutableListOf<Exception>()
        val mockConn = MockServerConnection()

        val typed = DefaultTypedServerConnection(
            connection = mockConn,
            actionCodec = FailingActionCodec(),
            messageCodec = goodMessageCodec,
            onDecodeError = { errors.add(it) },
        )

        val received = mutableListOf<TestAction>()
        val job = launch { typed.incoming.collect { received.add(it) } }
        yield()

        // Valid client message wrapping an action
        val clientMsg = goodMessageCodec.encodeActionMessage("""{"type":"io.flowdux.remote.serialization.DefaultTypedConnectionTest.TestAction.Ping","id":1}""")
        mockConn.emit(clientMsg)
        yield()

        assertEquals(1, errors.size)
        assertTrue(received.isEmpty())

        job.cancel()
    }

    @Test
    fun `server - null onDecodeError does not throw`() = runTest {
        val mockConn = MockServerConnection()

        val typed = DefaultTypedServerConnection(
            connection = mockConn,
            actionCodec = goodActionCodec,
            messageCodec = FailingMessageCodec(),
            onDecodeError = null,
        )

        val received = mutableListOf<TestAction>()
        val job = launch { typed.incoming.collect { received.add(it) } }
        yield()

        mockConn.emit("bad message")
        yield()

        assertTrue(received.isEmpty())

        job.cancel()
    }

    @Test
    fun `server - processing continues after decode error`() = runTest {
        val errors = mutableListOf<Exception>()
        val mockConn = MockServerConnection()

        val typed = DefaultTypedServerConnection(
            connection = mockConn,
            actionCodec = goodActionCodec,
            messageCodec = goodMessageCodec,
            onDecodeError = { errors.add(it) },
        )

        val received = mutableListOf<TestAction>()
        val job = launch { typed.incoming.collect { received.add(it) } }
        yield()

        // Bad message
        mockConn.emit("not valid json")
        yield()

        // Good message
        val actionJson = goodActionCodec.encode(TestAction.Ping(99))
        val goodMsg = goodMessageCodec.encodeActionMessage(actionJson)
        mockConn.emit(goodMsg)
        yield()

        assertEquals(1, errors.size)
        assertEquals(1, received.size)
        assertEquals(TestAction.Ping(99), received[0])

        job.cancel()
    }
}
