package io.flowdux.sample.chat.client

import io.flowdux.remote.ConnectionState
import io.flowdux.remote.RemoteConnection
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class WebSocketConnection(
    private val host: String = "localhost",
    private val port: Int = 8080,
    private val path: String = "/chat",
    private val scope: CoroutineScope,
) : RemoteConnection {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val incomingChannel = Channel<String>(Channel.UNLIMITED)
    override val incoming: Flow<String> = incomingChannel.receiveAsFlow()

    private val outgoingChannel = Channel<String>(Channel.UNLIMITED)

    private var client: HttpClient? = null

    override suspend fun send(message: String) {
        outgoingChannel.send(message)
    }

    override suspend fun connect() {
        _connectionState.value = ConnectionState.CONNECTING

        val httpClient = HttpClient(CIO) {
            install(WebSockets)
        }
        client = httpClient

        scope.launch {
            try {
                httpClient.webSocket(host = host, port = port, path = path) {
                    _connectionState.value = ConnectionState.CONNECTED
                    println("[Client] Connected to ws://$host:$port$path")

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
                println("[Client] Connection error: ${e.message}")
            } finally {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        outgoingChannel.close()
        incomingChannel.close()
        client?.close()
        client = null
    }
}
