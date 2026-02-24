package io.flowdux.remote.ktor

import io.flowdux.remote.ConnectionState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorClientConnectionCommonTest {

    @Test
    fun initialStateIsDisconnected() = runTest {
        val connection = KtorWebSocketClientConnection("ws://localhost:8080/test")
        try {
            assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun factoryCreateWithCustomParamsInitializesConnection() = runTest {
        val connection = KtorWebSocketClientConnection.create(
            host = "example.com",
            port = 9090,
            path = "/ws",
            secure = false,
        )
        try {
            assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun factoryCreateWithSecureParamInitializesConnection() = runTest {
        val connection = KtorWebSocketClientConnection.create(
            host = "example.com",
            port = 443,
            path = "/ws",
            secure = true,
        )
        try {
            assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun factoryCreateWithDefaultsInitializesConnection() = runTest {
        val connection = KtorWebSocketClientConnection.create()
        try {
            assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun sendBeforeConnectThrowsIllegalState() = runTest {
        val connection = KtorWebSocketClientConnection("ws://localhost:8080/test")
        try {
            assertFailsWith<IllegalStateException> {
                connection.send("hello")
            }
        } finally {
            connection.disconnect()
        }
    }
}
