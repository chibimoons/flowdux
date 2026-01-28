package io.flowdux.sample.chat.server

import io.flowdux.Store
import io.flowdux.createStore
import io.flowdux.remote.server.ServerConnection
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState
import io.flowdux.sample.chat.chatReducer
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow

fun main() {
    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            webSocket("/chat") {
                println("[Server] Client connected")

                val store = createChatStore(this)
                store.dispatch(ChatAction.StartListening)

                try {
                    closeReason.await()
                } finally {
                    store.close()
                    println("[Server] Client disconnected")
                }
            }
        }
    }.start(wait = true)
}

private fun createChatStore(session: DefaultWebSocketServerSession): Store<ChatState, ChatAction> {
    val connection = object : ServerConnection {
        override val incoming = session.incoming.receiveAsFlow()
            .filterIsInstance<Frame.Text>()
            .map { it.readText() }

        override suspend fun send(message: String) {
            session.send(Frame.Text(message))
        }
    }
    return createStore(
        initialState = ChatState(),
        reducer = chatReducer,
        middlewares = listOf(ChatServerMiddleware(), ChatServerRemoteMiddleware(connection)),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}
