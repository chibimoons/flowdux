package io.flowdux.sample.chat.client

import io.flowdux.Store
import io.flowdux.createStore
import io.flowdux.remote.TypedClientConnection
import io.flowdux.remote.ktor.KtorWebSocketClientConnection
import io.flowdux.remote.serialization.typedJson
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatEvent
import io.flowdux.sample.chat.SharedChatAction
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

    // Set current user and connect
    store.dispatch(ClientChatAction.SetCurrentUser("Alice"))
    store.dispatch(ClientChatAction.Connect)
    delay(500)

    // Simulate chat
    println("--- Simulating chat ---")
    println()

    store.dispatch(SharedChatAction.JoinRoom("Alice"))
    delay(500)

    store.dispatch(SharedChatAction.JoinRoom("Bob"))
    delay(500)

    store.dispatch(SharedChatAction.SendMessage("Alice", "Hello everyone!"))
    delay(500)

    store.dispatch(SharedChatAction.SendMessage("Bob", "Hi Alice!"))
    delay(500)

    store.dispatch(SharedChatAction.SendMessage("Alice", "How are you?"))
    delay(500)

    store.dispatch(SharedChatAction.LeaveRoom("Bob"))
    delay(500)

    // Print final state
    println()
    println("--- Final State ---")
    val finalState = store.currentState
    println("Current user: ${finalState.currentUser}")
    println("Users online: ${finalState.users}")
    println("Message history:")
    for (msg in finalState.messages) {
        println("  [${msg.user}] ${msg.text}")
    }

    // Cleanup
    collectorJob.cancel()
    store.dispatch(ClientChatAction.Disconnect)
    store.close()

    println()
    println("=== Demo Complete ===")
}

@Suppress("UNCHECKED_CAST")
private fun createChatStore(): Store<ClientChatState, ChatAction> {
    val connection = KtorWebSocketClientConnection.create(
        host = "localhost",
        port = 8080,
        path = "/chat",
    ).typedJson<SharedChatAction>() as TypedClientConnection<ChatAction>
    return createStore(
        initialState = ClientChatState(),
        reducer = clientChatReducer,
        middlewares = listOf(ChatRemoteMiddleware(connection)),
    )
}
