package io.flowdux.remote.ktor

import io.flowdux.remote.ConnectionState
import io.flowdux.remote.ClientConnection
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Ktor-based WebSocket client implementation of [ClientConnection].
 *
 * This class provides a multiplatform WebSocket connection using Ktor client.
 * It handles connection lifecycle, message sending/receiving, and exposes
 * connection state as a reactive flow.
 *
 * [connect] suspends until the connection is closed — the caller is responsible
 * for launching it in an appropriate coroutine scope.
 *
 * @param url WebSocket URL to connect to (e.g., "ws://localhost:8080/path" or "wss://example.com/path")
 * @param httpClient Optional pre-configured HttpClient. If not provided, uses platform-default engine.
 */
class KtorWebSocketClientConnection(
    private val url: String,
    httpClient: HttpClient? = null,
) : ClientConnection {

    private val httpClient: HttpClient
    private val isClientOwned: Boolean

    init {
        if (httpClient != null) {
            this.httpClient = httpClient
            this.isClientOwned = false
        } else {
            this.httpClient = createDefaultHttpClient()
            this.isClientOwned = true
        }
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val incomingChannel = Channel<String>(Channel.UNLIMITED)
    override val incoming: Flow<String> = incomingChannel.receiveAsFlow()

    private val outgoingChannel = Channel<String>(Channel.UNLIMITED)

    override suspend fun send(message: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            throw IllegalStateException("Cannot send message: WebSocket is not connected")
        }
        outgoingChannel.send(message)
    }

    override suspend fun connect() {
        // Prevent multiple concurrent connections
        if (_connectionState.value != ConnectionState.DISCONNECTED) {
            return
        }
        _connectionState.value = ConnectionState.CONNECTING

        try {
            httpClient.webSocket(url) {
                _connectionState.value = ConnectionState.CONNECTED

                val receiveJob = launch {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            incomingChannel.send(text)
                        }
                    }
                }

                val sendJob = launch {
                    for (message in outgoingChannel) {
                        send(Frame.Text(message))
                    }
                }

                receiveJob.join()
                sendJob.cancel()
            }
        } catch (e: Exception) {
            // Connection error - could add logging or error callback
        } finally {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        outgoingChannel.close()
        incomingChannel.close()
        if (isClientOwned) {
            httpClient.close()
        }
    }

    companion object {
        /**
         * Create a [KtorWebSocketClientConnection] with host, port, and path components.
         *
         * @param host Server hostname (default: "localhost")
         * @param port Server port (default: 8080)
         * @param path WebSocket path (default: "/")
         * @param secure Whether to use WSS (default: false)
         */
        fun create(
            host: String = "localhost",
            port: Int = 8080,
            path: String = "/",
            secure: Boolean = false,
        ): KtorWebSocketClientConnection {
            val scheme = if (secure) "wss" else "ws"
            val url = "$scheme://$host:$port$path"
            return KtorWebSocketClientConnection(url)
        }
    }
}

/**
 * Platform-specific factory for creating default HttpClient with WebSocket support.
 */
internal expect fun createDefaultHttpClient(): HttpClient
