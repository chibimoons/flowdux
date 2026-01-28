package io.flowdux.sample.chat.client

import io.flowdux.createStore
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

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val connection = WebSocketConnection(scope = scope)
    val middleware = ChatRemoteMiddleware(connection, scope)
    val store = createStore(
        initialState = ChatState(),
        reducer = chatReducer,
        middlewares = listOf(middleware),
        scope = scope,
    )
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
