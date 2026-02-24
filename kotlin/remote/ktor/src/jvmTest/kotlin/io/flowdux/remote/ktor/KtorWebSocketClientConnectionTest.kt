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
import kotlinx.coroutines.flow.first
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
}
