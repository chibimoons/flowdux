package io.flowdux.sample.chat.server

import io.flowdux.createStore
import io.flowdux.remote.server.ResponseCollector
import io.flowdux.remote.server.ServerSessionHandler
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

fun createChatSessionHandler(): ServerSessionHandler<ChatState, ChatAction> {
    return ServerSessionHandler(
        storeFactory = {
            val collector = ResponseCollector<ChatState, ChatAction>()
            val store = createStore(
                initialState = ChatState(),
                reducer = chatReducer,
                middlewares = listOf(ChatServerMiddleware()),
                logger = collector,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
            Pair(store, collector)
        },
        actionCodec = io.flowdux.sample.chat.ChatActionCodec(),
    )
}

fun main() {
    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            webSocket("/chat") {
                println("[Server] Client connected")
                val handler = createChatSessionHandler()
                handler.initialize()

                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val message = frame.readText()
                            println("[Server] Received: $message")
                            val response = handler.handleMessage(message)
                            println("[Server] Sending: $response")
                            send(Frame.Text(response))
                        }
                    }
                } finally {
                    handler.close()
                    println("[Server] Client disconnected")
                }
            }
        }
    }.start(wait = true)

    println("Chat WebSocket server started on ws://localhost:8080/chat")
}
