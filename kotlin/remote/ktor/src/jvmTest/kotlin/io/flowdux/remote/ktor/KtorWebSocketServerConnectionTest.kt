package io.flowdux.remote.ktor

import io.flowdux.remote.ConnectionState
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class KtorWebSocketServerConnectionTest {

    @Test
    fun `send after session close does not throw`() = runBlocking {
        var serverConnection: KtorWebSocketServerConnection? = null

        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/test") {
                    serverConnection = KtorWebSocketServerConnection(this)
                    // Wait a bit to allow test to capture the connection
                    delay(100)
                    // Server explicitly closes its session
                    close(CloseReason(CloseReason.Codes.NORMAL, "Test close"))
                }
            }
        }.start(wait = false)

        try {
            val port = server.resolvedConnectors().first().port
            val clientConnection = KtorWebSocketClientConnection("ws://localhost:$port/test")

            val connectJob = launch { clientConnection.connect() }
            withTimeout(5_000) {
                clientConnection.connectionState.first { it == ConnectionState.CONNECTED }
            }

            // Wait for server to capture connection and close
            delay(500)

            // This should NOT throw ClosedSendChannelException
            serverConnection?.send("message after close")

            clientConnection.disconnect()
            connectJob.join()
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `messages are received by client`() = runBlocking {
        var serverConnection: KtorWebSocketServerConnection? = null

        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/test") {
                    serverConnection = KtorWebSocketServerConnection(this)
                    for (frame in incoming) { /* keep alive */ }
                }
            }
        }.start(wait = false)

        try {
            val port = server.resolvedConnectors().first().port
            val clientConnection = KtorWebSocketClientConnection("ws://localhost:$port/test")

            val connectJob = launch { clientConnection.connect() }
            withTimeout(5_000) {
                clientConnection.connectionState.first { it == ConnectionState.CONNECTED }
            }

            // Wait for server to capture connection
            delay(100)

            serverConnection?.send("hello from server")
            val received = withTimeout(5_000) {
                clientConnection.incoming.first()
            }
            assertEquals("hello from server", received)

            clientConnection.disconnect()
            connectJob.join()
        } finally {
            server.stop(500, 1_000)
        }
    }
}
