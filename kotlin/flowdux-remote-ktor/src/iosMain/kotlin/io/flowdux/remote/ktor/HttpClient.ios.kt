package io.flowdux.remote.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun createDefaultHttpClient(): HttpClient = HttpClient(Darwin) {
    install(WebSockets)
}
