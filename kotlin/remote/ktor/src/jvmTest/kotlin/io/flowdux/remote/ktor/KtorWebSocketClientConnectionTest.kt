package io.flowdux.remote.ktor

import io.flowdux.remote.ConnectionState
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KtorWebSocketClientConnectionTest {

    @Test
    fun `connect transitions state to CONNECTED`() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/test") {
                    for (frame in incoming) { /* keep alive */ }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            val connection = KtorWebSocketClientConnection("ws://localhost:$port/test")
            assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)

            val connectJob = launch(Dispatchers.Default) { connection.connect() }
            withTimeout(5_000) {
                connection.connectionState.first { it == ConnectionState.CONNECTED }
            }
            assertEquals(ConnectionState.CONNECTED, connection.connectionState.value)

            connection.disconnect()
            connectJob.join()
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `disconnect causes connect to return`() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/test") {
                    for (frame in incoming) { /* keep alive */ }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            val connection = KtorWebSocketClientConnection("ws://localhost:$port/test")

            val connectJob = launch(Dispatchers.Default) { connection.connect() }
            withTimeout(5_000) {
                connection.connectionState.first { it == ConnectionState.CONNECTED }
            }

            connection.disconnect()

            // Key assertion: connect() MUST return after disconnect()
            withTimeout(5_000) {
                connectJob.join()
            }
            assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `messages are exchanged before disconnect`() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/test") {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            send(Frame.Text("echo:${frame.readText()}"))
                        }
                    }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            val connection = KtorWebSocketClientConnection("ws://localhost:$port/test")

            val connectJob = launch(Dispatchers.Default) { connection.connect() }
            withTimeout(5_000) {
                connection.connectionState.first { it == ConnectionState.CONNECTED }
            }

            connection.send("hello")
            val received = withTimeout(5_000) {
                connection.incoming.first()
            }
            assertEquals("echo:hello", received)

            connection.disconnect()
            connectJob.join()
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `disconnect closes server-side session`() = runBlocking {
        val serverSessionEnded = AtomicBoolean(false)

        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/test") {
                    try {
                        for (frame in incoming) { /* keep alive */ }
                    } finally {
                        serverSessionEnded.set(true)
                    }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            val connection = KtorWebSocketClientConnection("ws://localhost:$port/test")

            val connectJob = launch(Dispatchers.Default) { connection.connect() }
            withTimeout(5_000) {
                connection.connectionState.first { it == ConnectionState.CONNECTED }
            }

            connection.disconnect()
            withTimeout(5_000) { connectJob.join() }

            // Allow server to process the close frame
            delay(500)

            assertTrue(serverSessionEnded.get(), "Server session should end after client disconnect")
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `concurrent connect calls create only one connection`() = runBlocking {
        val connectionCount = AtomicInteger(0)
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/test") {
                    connectionCount.incrementAndGet()
                    for (frame in incoming) { /* keep alive */ }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            val connection = KtorWebSocketClientConnection("ws://localhost:$port/test")

            // Launch 10 coroutines that all call connect() concurrently
            val jobs = (1..10).map {
                launch(Dispatchers.Default) { connection.connect() }
            }

            // Wait for connection to be established
            withTimeout(5_000) {
                connection.connectionState.first { it == ConnectionState.CONNECTED }
            }
            // Allow time for any extra connections to be established
            delay(1_000)

            assertEquals(1, connectionCount.get(), "Only one WebSocket connection should be created")

            connection.disconnect()
            jobs.forEach { it.join() }
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `connect to unreachable server returns to DISCONNECTED`() = runBlocking {
        // Use localhost with a closed port for fast, deterministic failure
        val connection = KtorWebSocketClientConnection("ws://127.0.0.1:1/test")
        assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)

        // connect() should throw or return, but state must return to DISCONNECTED
        try {
            withTimeout(3_000) { connection.connect() }
        } catch (_: Exception) {
            // Expected: connection failure
        } finally {
            connection.disconnect()
        }

        assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
    }

    @Test
    fun `reconnect after disconnect succeeds`() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/test") {
                    for (frame in incoming) { /* keep alive */ }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port

            // First connection
            val connection1 = KtorWebSocketClientConnection("ws://localhost:$port/test")
            val job1 = launch(Dispatchers.Default) { connection1.connect() }
            withTimeout(5_000) {
                connection1.connectionState.first { it == ConnectionState.CONNECTED }
            }
            connection1.disconnect()
            withTimeout(5_000) { job1.join() }
            assertEquals(ConnectionState.DISCONNECTED, connection1.connectionState.value)

            // Second connection (new instance, same server)
            val connection2 = KtorWebSocketClientConnection("ws://localhost:$port/test")
            val job2 = launch(Dispatchers.Default) { connection2.connect() }
            withTimeout(5_000) {
                connection2.connectionState.first { it == ConnectionState.CONNECTED }
            }
            assertEquals(ConnectionState.CONNECTED, connection2.connectionState.value)

            connection2.disconnect()
            withTimeout(5_000) { job2.join() }
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `server initiated close allows client to detect and disconnect`() = runBlocking {
        val serverClosed = AtomicBoolean(false)

        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/test") {
                    delay(200)
                    close(CloseReason(CloseReason.Codes.NORMAL, "Server closing"))
                    serverClosed.set(true)
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            val connection = KtorWebSocketClientConnection("ws://localhost:$port/test")

            val connectJob = launch(Dispatchers.Default) { connection.connect() }
            withTimeout(5_000) {
                connection.connectionState.first { it == ConnectionState.CONNECTED }
            }

            // Wait for server to close by polling the flag
            withTimeout(5_000) {
                while (!serverClosed.get()) {
                    delay(10)
                }
            }

            // Client calls disconnect to clean up
            connection.disconnect()
            withTimeout(5_000) { connectJob.join() }
            assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `server initiated close terminates connect without explicit disconnect`() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/test") {
                    delay(200)
                    close(CloseReason(CloseReason.Codes.NORMAL, "Server closing"))
                }
            }
        }.start(wait = false)

        var connection: KtorWebSocketClientConnection? = null
        try {
            val port = server.engine.resolvedConnectors().first().port
            connection = KtorWebSocketClientConnection("ws://localhost:$port/test")

            val connectJob = launch(Dispatchers.Default) { connection.connect() }
            withTimeout(5_000) {
                connection.connectionState.first { it == ConnectionState.CONNECTED }
            }

            // connect() should return on its own after the server closes
            withTimeout(5_000) { connectJob.join() }
            assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
        } finally {
            connection?.disconnect()
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `disconnect during active message reception completes cleanly`() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/test") {
                    // Stream messages continuously to keep the receive loop busy
                    var i = 0
                    while (true) {
                        send(Frame.Text("msg-${i++}"))
                        delay(10)
                    }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            val connection = KtorWebSocketClientConnection("ws://localhost:$port/test")

            val connectJob = launch(Dispatchers.Default) { connection.connect() }
            withTimeout(5_000) {
                connection.connectionState.first { it == ConnectionState.CONNECTED }
            }

            // Wait for some messages to flow through
            withTimeout(5_000) { connection.incoming.first() }

            // Disconnect while the receive loop is actively processing frames.
            // This exercises the ClosedSendChannelException race condition path.
            connection.disconnect()
            withTimeout(5_000) { connectJob.join() }
            assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `incoming burst beyond buffer size delivers all messages without loss`() = runBlocking {
        val messageCount = 100
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/test") {
                    // Send messages as fast as possible to overflow the buffer (64)
                    for (i in 0 until messageCount) {
                        send(Frame.Text("msg-$i"))
                    }
                    // Keep connection alive until client disconnects
                    for (frame in incoming) { /* keep alive */ }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            val connection = KtorWebSocketClientConnection("ws://localhost:$port/test")

            val connectJob = launch(Dispatchers.Default) { connection.connect() }
            withTimeout(5_000) {
                connection.connectionState.first { it == ConnectionState.CONNECTED }
            }

            // Collect all messages at once. The server sends 100 messages which exceeds
            // the buffer capacity (64). SUSPEND overflow policy ensures no messages are
            // dropped — the server's send() suspends until buffer space is available.
            val received = withTimeout(10_000) {
                connection.incoming.take(messageCount).toList()
            }

            assertEquals(messageCount, received.size)
            for (i in 0 until messageCount) {
                assertEquals("msg-$i", received[i], "Message $i out of order or missing")
            }

            connection.disconnect()
            connectJob.join()
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `outgoing messages beyond buffer size are all delivered to slow server`() = runBlocking {
        val messageCount = 100
        val serverReceived = AtomicInteger(0)

        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/test") {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            serverReceived.incrementAndGet()
                            // Slow server: delays reading to let the outgoing buffer fill up.
                            // This forces the client's send() to suspend when the buffer (64) is full.
                            delay(10)
                        }
                    }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            val connection = KtorWebSocketClientConnection("ws://localhost:$port/test")

            val connectJob = launch(Dispatchers.Default) { connection.connect() }
            withTimeout(5_000) {
                connection.connectionState.first { it == ConnectionState.CONNECTED }
            }

            // Send more messages than the buffer can hold (64).
            // With the slow server, the outgoing buffer fills up and send() suspends
            // until the server drains messages. No messages should be dropped.
            withTimeout(30_000) {
                for (i in 0 until messageCount) {
                    connection.send("msg-$i")
                }
            }

            // Wait for server to finish processing all messages
            withTimeout(10_000) {
                while (serverReceived.get() < messageCount) {
                    delay(50)
                }
            }

            assertEquals(messageCount, serverReceived.get())

            connection.disconnect()
            connectJob.join()
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `slow consumer receives messages in order under sustained backpressure`() = runBlocking {
        val messageCount = 200 // Well above buffer size of 64
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/test") {
                    for (i in 0 until messageCount) {
                        send(Frame.Text("msg-$i"))
                    }
                    // Keep alive until client disconnects
                    for (frame in incoming) { /* keep alive */ }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            val connection = KtorWebSocketClientConnection("ws://localhost:$port/test")

            val connectJob = launch(Dispatchers.Default) { connection.connect() }
            withTimeout(5_000) {
                connection.connectionState.first { it == ConnectionState.CONNECTED }
            }

            // Collect slowly with periodic delays to exercise buffer fill/drain cycles.
            // With BUFFERED (64, SUSPEND), the receive loop suspends when the buffer fills,
            // then resumes as the consumer drains it. No messages should be dropped.
            val consumed = mutableListOf<String>()
            withTimeout(30_000) {
                connection.incoming.take(messageCount).collect { msg ->
                    consumed.add(msg)
                    if (consumed.size % 10 == 0) delay(50)
                }
            }

            assertEquals(messageCount, consumed.size)
            for (i in 0 until messageCount) {
                assertEquals("msg-$i", consumed[i], "Message $i out of order or missing")
            }

            connection.disconnect()
            connectJob.join()
        } finally {
            server.stop(500, 1_000)
        }
    }
}
