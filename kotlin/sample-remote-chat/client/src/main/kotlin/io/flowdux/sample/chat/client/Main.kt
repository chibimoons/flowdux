package io.flowdux.sample.chat.client

import io.flowdux.Store
import io.flowdux.createStore
import io.flowdux.remote.ktor.KtorWebSocketConnection
import io.flowdux.remote.serialization.actionCodecOf
import io.flowdux.remote.typed
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatEvent
import io.flowdux.sample.chat.ChatState
import io.flowdux.sample.chat.chatReducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("=== Flowdux Remote Chat Demo ===")
    println()

    val store = createChatStore()

    // Observe state changes
    val collectorJob = launch {
        store.state.collect { state ->
            when (val event = state.lastEvent) {
                is ChatEvent.UserJoined -> println("[System] ${event.user} joined the room")
                is ChatEvent.UserLeft -> println("[System] ${event.user} left the room")
                is ChatEvent.MessageReceived -> println("[${event.user}] ${event.text}")
                null -> {}
            }
        }
    }

    // Connect to server
    store.dispatch(ChatAction.Connect)
    delay(500)

    // Simulate chat
    println("--- Simulating chat ---")
    println()

    store.dispatch(ChatAction.JoinRoom("Alice"))
    delay(500)

    store.dispatch(ChatAction.JoinRoom("Bob"))
    delay(500)

    store.dispatch(ChatAction.SendMessage("Alice", "Hello everyone!"))
    delay(500)

    store.dispatch(ChatAction.SendMessage("Bob", "Hi Alice!"))
    delay(500)

    store.dispatch(ChatAction.SendMessage("Alice", "How are you?"))
    delay(500)

    store.dispatch(ChatAction.LeaveRoom("Bob"))
    delay(500)

    // Print final state
    println()
    println("--- Final State ---")
    val finalState = store.currentState
    println("Users online: ${finalState.users}")
    println("Message history:")
    for (msg in finalState.messages) {
        println("  [${msg.user}] ${msg.text}")
    }

    // Cleanup
    collectorJob.cancel()
    store.dispatch(ChatAction.Disconnect)
    store.close()

    println()
    println("=== Demo Complete ===")
}

private fun createChatStore(): Store<ChatState, ChatAction> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val connection = KtorWebSocketConnection.create(
        host = "localhost",
        port = 8080,
        path = "/chat",
        scope = scope,
    )
    val typedConnection = connection.typed(actionCodecOf<ChatAction>())
    return createStore(
        initialState = ChatState(),
        reducer = chatReducer,
        middlewares = listOf(ChatRemoteMiddleware(typedConnection, scope)),
        scope = scope,
    )
}
