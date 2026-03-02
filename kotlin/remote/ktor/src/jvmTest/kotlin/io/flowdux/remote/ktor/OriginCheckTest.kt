package io.flowdux.remote.ktor

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class OriginCheckTest {

    private val allowedOrigin = "https://example.com"

    private val policy = OriginPolicy.AllowList(
        origins = setOf(allowedOrigin),
    )

    private fun createClient() = HttpClient { install(ClientWebSockets) }

    @Test
    fun `allowed origin connects successfully`() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocketWithOriginCheck("/ws", policy) {
                    send(Frame.Text("hello"))
                    for (frame in incoming) { /* keep alive */ }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            createClient().use { client ->
                client.webSocket(
                    host = "localhost",
                    port = port,
                    path = "/ws",
                    request = { header("Origin", allowedOrigin) },
                ) {
                    val frame = withTimeout(5_000) { incoming.receive() } as Frame.Text
                    assertEquals("hello", frame.readText())
                }
            }
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `rejected origin receives VIOLATED_POLICY close`() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocketWithOriginCheck("/ws", policy) {
                    send(Frame.Text("should not reach"))
                    for (frame in incoming) { /* keep alive */ }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            createClient().use { client ->
                client.webSocket(
                    host = "localhost",
                    port = port,
                    path = "/ws",
                    request = { header("Origin", "https://evil.com") },
                ) {
                    val reason = withTimeout(5_000) { closeReason.await() }
                    assertNotNull(reason)
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason.code)
                }
            }
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `null origin is rejected by AllowList with default config`() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocketWithOriginCheck("/ws", policy) {
                    send(Frame.Text("should not reach"))
                    for (frame in incoming) { /* keep alive */ }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            createClient().use { client ->
                // No Origin header sent
                client.webSocket(
                    host = "localhost",
                    port = port,
                    path = "/ws",
                ) {
                    val reason = withTimeout(5_000) { closeReason.await() }
                    assertNotNull(reason)
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason.code)
                }
            }
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `AllowAll policy permits any origin`() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocketWithOriginCheck("/ws", OriginPolicy.AllowAll) {
                    send(Frame.Text("welcome"))
                    for (frame in incoming) { /* keep alive */ }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            createClient().use { client ->
                client.webSocket(
                    host = "localhost",
                    port = port,
                    path = "/ws",
                    request = { header("Origin", "https://any-site.com") },
                ) {
                    val frame = withTimeout(5_000) { incoming.receive() } as Frame.Text
                    assertEquals("welcome", frame.readText())
                }
            }
        } finally {
            server.stop(500, 1_000)
        }
    }

    @Test
    fun `checkOrigin can be used standalone inside webSocket handler`() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    if (!checkOrigin(policy)) return@webSocket
                    send(Frame.Text("passed"))
                    for (frame in incoming) { /* keep alive */ }
                }
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            createClient().use { client ->
                // Allowed origin
                client.webSocket(
                    host = "localhost",
                    port = port,
                    path = "/ws",
                    request = { header("Origin", allowedOrigin) },
                ) {
                    val frame = withTimeout(5_000) { incoming.receive() } as Frame.Text
                    assertEquals("passed", frame.readText())
                }
            }
        } finally {
            server.stop(500, 1_000)
        }
    }
}
