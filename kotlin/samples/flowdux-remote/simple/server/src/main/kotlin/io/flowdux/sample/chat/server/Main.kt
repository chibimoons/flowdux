package io.flowdux.sample.chat.server

import io.flowdux.Middleware
import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.serialization.typedJsonAs
import io.flowdux.remote.server.pattern.createSingleClientServer
import io.flowdux.remote.server.serve
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState
import io.flowdux.sample.chat.SharedChatAction
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*

fun main() {
    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            webSocket("/chat") {
                println("[Server] Client connected")

                val connection = KtorWebSocketServerConnection(this)
                    .typedJsonAs<SharedChatAction, ChatAction>()

                val server = createSingleClientServer(
                    initialState = ServerChatState(),
                    reducer = serverChatReducer,
                    connection = connection,
                    processors = chatProcessors(),
                )

                server.serve { serverState ->
                    SharedChatAction.SyncState(
                        ChatState(
                            messages = serverState.messages,
                            users = serverState.users,
                            lastEvent = serverState.lastEvent,
                        ),
                    )
                }
            }
        }
    }.start(wait = true)
}

private fun chatProcessors() = Middleware.ActionProcessorBuilder<ServerChatState, ChatAction>().apply {
    on<SharedChatAction.SendMessage> { _, action ->
        emit(ServerChatAction.MessageReceived(user = action.user, text = action.text))
    }
    on<SharedChatAction.JoinRoom> { _, action ->
        emit(ServerChatAction.UserJoined(user = action.user))
    }
    on<SharedChatAction.LeaveRoom> { _, action ->
        emit(ServerChatAction.UserLeft(user = action.user))
    }
}.build()
