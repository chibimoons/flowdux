package io.flowdux.sample.chat.web

import io.flowdux.remote.ClientConnection
import io.flowdux.remote.ConnectionState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import kotlin.coroutines.resume

/**
 * Browser-native WebSocket implementation of [ClientConnection].
 *
 * Uses the browser's built-in `WebSocket` API instead of Ktor,
 * since `flowdux-remote-ktor` does not currently provide a JS target.
 */
class BrowserWebSocketClientConnection(
    private val url: String,
) : ClientConnection {

    private var webSocket: WebSocket? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val incomingChannel = Channel<String>(Channel.UNLIMITED)
    override val incoming: Flow<String> = incomingChannel.receiveAsFlow()

    override suspend fun send(message: String) {
        val ws = webSocket
        if (ws == null || _connectionState.value != ConnectionState.CONNECTED) {
            throw IllegalStateException("Cannot send message: WebSocket is not connected")
        }
        ws.send(message)
    }

    override suspend fun connect() {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return

        _connectionState.value = ConnectionState.CONNECTING

        val ws = WebSocket(url)
        webSocket = ws

        ws.onopen = {
            _connectionState.value = ConnectionState.CONNECTED
        }

        ws.onmessage = { event ->
            val data = event.data as? String
            if (data != null) {
                incomingChannel.trySend(data)
            }
        }

        ws.onerror = { _: Event ->
            // Error will be followed by onclose
        }

        ws.onclose = {
            _connectionState.value = ConnectionState.DISCONNECTED
            webSocket = null
        }

        // Suspend until disconnected
        suspendCancellableCoroutine { cont ->
            val originalOnClose = ws.onclose
            ws.onclose = { event ->
                @Suppress("UNCHECKED_CAST")
                (originalOnClose as? (Event) -> Unit)?.invoke(event as Event)
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation {
                ws.close()
            }
        }
    }

    override suspend fun disconnect() {
        webSocket?.close()
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
