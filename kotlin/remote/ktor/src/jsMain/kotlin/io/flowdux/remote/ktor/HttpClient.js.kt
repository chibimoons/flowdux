package io.flowdux.remote.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun createDefaultHttpClient(): HttpClient = HttpClient(Js) {
    install(WebSockets)
}
