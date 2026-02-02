package io.flowdux.sample.chat.multiserver

import io.flowdux.Middleware
import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.serialization.typedJson
import io.flowdux.remote.server.TypedServerConnection
import io.flowdux.remote.server.createRemoteServer
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState
import io.flowdux.sample.chat.SharedChatAction
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

fun main() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Single session serves ALL clients (one Store, shared state)
    val session = createRemoteServer(
        initialState = ServerChatState(),
        reducer = serverChatReducer,
        processors = chatProcessors(),
        stateMapper = { serverState ->
            SharedChatAction.SyncState(
                ChatState(
                    messages = serverState.messages,
                    users = serverState.users,
                    lastEvent = serverState.lastEvent,
                )
            )
        },
        scope = applicationScope,
    )

    // Monitor state changes
    applicationScope.launch {
        session.state.collect { state ->
            println("[Server] clients=${runCatching { session.sessionCount() }.getOrDefault(0)}, " +
                "users=${state.users}, messages=${state.totalMessagesProcessed}")
        }
    }

    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            webSocket("/chat") {
                val sessionId = UUID.randomUUID().toString()
                println("[Server] Client connected: $sessionId")

                @Suppress("UNCHECKED_CAST")
                val connection = KtorWebSocketServerConnection(this)
                    .typedJson<SharedChatAction>() as TypedServerConnection<ChatAction>

                try {
                    session.handleClient(sessionId, connection)
                } finally {
                    println("[Server] Client disconnected: $sessionId")
                }
            }
        }
    }.start(wait = true)

    session.close()
}

private fun chatProcessors() =
    Middleware.ActionProcessorBuilder<ServerChatState, ChatAction>().apply {
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
