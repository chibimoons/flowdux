package io.flowdux.remote.ktor

import io.flowdux.remote.ConnectionState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorClientConnectionCommonTest {

    @Test
    fun initialStateIsDisconnected() {
        val connection = KtorWebSocketClientConnection("ws://localhost:8080/test")
        assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
    }

    @Test
    fun factoryCreateBuildsCorrectWsUrl() {
        val connection = KtorWebSocketClientConnection.create(
            host = "example.com",
            port = 9090,
            path = "/ws",
            secure = false,
        )
        assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
    }

    @Test
    fun factoryCreateBuildsSecureWssUrl() {
        val connection = KtorWebSocketClientConnection.create(
            host = "example.com",
            port = 443,
            path = "/ws",
            secure = true,
        )
        assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
    }

    @Test
    fun factoryCreateUsesDefaults() {
        val connection = KtorWebSocketClientConnection.create()
        assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
    }

    @Test
    fun sendBeforeConnectThrowsIllegalState() = runTest {
        val connection = KtorWebSocketClientConnection("ws://localhost:8080/test")
        assertFailsWith<IllegalStateException> {
            connection.send("hello")
        }
    }
}
