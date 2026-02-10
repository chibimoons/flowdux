package io.flowdux.sample.chat.multiclient

import io.flowdux.Store
import io.flowdux.remote.createClientStore
import io.flowdux.remote.ktor.KtorWebSocketClientConnection
import io.flowdux.remote.serialization.typedJson
import io.flowdux.remote.serialization.upcast
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatEvent
import io.flowdux.sample.chat.SharedChatAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

fun main(args: Array<String>) = runBlocking {
    val username = args.firstOrNull() ?: run {
        print("Enter your name: ")
        System.out.flush()
        readlnOrNull()?.trim()?.ifEmpty { null }
    } ?: run {
        println("No name provided. Exiting.")
        return@runBlocking
    }

    println()
    println("=== Flowdux Multi-Client Chat ===")
    println("User: $username")
    println("Commands: type a message and press Enter, or /quit to exit")
    println()

    val store = createChatStore()

    // Observe events
    var lastAnnouncement: String? = null
    val collectorJob = launch {
        store.state.collect { state ->
            // Show system announcements
            if (state.systemAnnouncement != null && state.systemAnnouncement != lastAnnouncement) {
                lastAnnouncement = state.systemAnnouncement
                println()
                println("  *** SYSTEM: ${state.systemAnnouncement} ***")
                println()
            }

            when (val event = state.lastEvent) {
                is ChatEvent.UserJoined ->
                    println("  * ${event.user} joined (online: ${state.users})")
                is ChatEvent.UserLeft ->
                    println("  * ${event.user} left (online: ${state.users})")
                is ChatEvent.MessageReceived ->
                    println("  [${event.user}] ${event.text}")
                null -> {}
            }
        }
    }

    // Connect and join
    store.dispatch(ClientChatAction.SetCurrentUser(username))
    store.dispatch(ClientChatAction.Connect)
    delay(500)
    store.dispatch(SharedChatAction.JoinRoom(username))
    delay(200)

    // Interactive input loop
    withContext(Dispatchers.IO) {
        while (true) {
            val line = readlnOrNull() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.equals("/quit", ignoreCase = true)) break

            if (trimmed.equals("/users", ignoreCase = true)) {
                val users = store.currentState.users
                println("  Online: $users")
                continue
            }

            if (trimmed.equals("/history", ignoreCase = true)) {
                val messages = store.currentState.messages
                if (messages.isEmpty()) {
                    println("  (no messages yet)")
                } else {
                    messages.forEach { println("  [${it.user}] ${it.text}") }
                }
                continue
            }

            store.dispatch(SharedChatAction.SendMessage(username, trimmed))
        }
    }

    // Cleanup
    println()
    println("Leaving room...")
    store.dispatch(SharedChatAction.LeaveRoom(username))
    delay(300)

    collectorJob.cancel()
    store.dispatch(ClientChatAction.Disconnect)
    store.close()

    println("Bye!")
}

private fun createChatStore(): Store<ClientChatState, ChatAction> {
    val connection = KtorWebSocketClientConnection.create(
        host = "localhost",
        port = 8080,
        path = "/chat",
    ).typedJson<SharedChatAction>().upcast<SharedChatAction, ChatAction>()
    return createClientStore(
        initialState = ClientChatState(),
        syncMiddleware = ChatRemoteMiddleware(connection),
        reducer = clientChatReducer,
    )
}
