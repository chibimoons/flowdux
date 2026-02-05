package io.flowdux.sample.chat.server

import io.flowdux.Store
import io.flowdux.createStore
import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.serialization.typedJson
import io.flowdux.remote.serialization.upcast
import io.flowdux.remote.server.serve
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState
import io.flowdux.sample.chat.SharedChatAction
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*

fun main() {
    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            webSocket("/chat") {
                println("[Server] Client connected")
                createChatStore(this).serve { serverState ->
                    SharedChatAction.SyncState(
                        ChatState(
                            messages = serverState.messages,
                            users = serverState.users,
                            lastEvent = serverState.lastEvent,
                        )
                    )
                }
            }
        }
    }.start(wait = true)
}

private fun createChatStore(session: DefaultWebSocketServerSession): Store<ServerChatState, ChatAction> {
    val typedConnection = KtorWebSocketServerConnection(session)
        .typedJson<SharedChatAction>()
        .upcast<SharedChatAction, ChatAction>()
    return createStore(
        initialState = ServerChatState(),
        reducer = serverChatReducer,
        middlewares = listOf(ChatServerRemoteMiddleware(typedConnection)),
    )
}
