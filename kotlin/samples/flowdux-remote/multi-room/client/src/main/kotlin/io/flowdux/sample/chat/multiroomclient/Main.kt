package io.flowdux.sample.chat.multiroomclient

import io.flowdux.Store
import io.flowdux.createStore
import io.flowdux.remote.TypedClientConnection
import io.flowdux.remote.ktor.KtorWebSocketClientConnection
import io.flowdux.remote.serialization.typedJson
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatEvent
import io.flowdux.sample.chat.SharedChatAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Multi-Room Chat Client Demo
 *
 * This sample demonstrates connecting to different rooms on the server.
 * Each room has independent state - messages in one room don't appear in another.
 *
 * Commands:
 * - /join <room> — Join a different room
 * - /rooms — Show available rooms
 * - /users — Show users in current room
 * - /history — Show message history
 * - /quit — Exit
 */
fun main(args: Array<String>) = runBlocking {
    val username = args.firstOrNull() ?: run {
        print("Enter your name: ")
        System.out.flush()
        readlnOrNull()?.trim()?.ifEmpty { null }
    } ?: run {
        println("No name provided. Exiting.")
        return@runBlocking
    }

    val initialRoom = args.getOrNull(1) ?: "general"

    println("""

        ╔══════════════════════════════════════════════════╗
        ║     FlowDux Multi-Room Chat Client               ║
        ╠══════════════════════════════════════════════════╣
        ║  Commands:                                       ║
        ║    /join <room>  - Join a different room         ║
        ║    /rooms        - List suggested rooms          ║
        ║    /users        - Show users in current room    ║
        ║    /history      - Show message history          ║
        ║    /quit         - Exit                          ║
        ╠══════════════════════════════════════════════════╣
        ║  Just type to send a message!                    ║
        ╚══════════════════════════════════════════════════╝

    """.trimIndent())

    var currentRoom = initialRoom
    var store: Store<ClientChatState, ChatAction>? = null
    var collectorJob: Job? = null

    suspend fun connectToRoom(roomId: String): Store<ClientChatState, ChatAction> {
        // Cleanup previous connection
        collectorJob?.cancel()
        store?.let {
            it.dispatch(SharedChatAction.LeaveRoom(username))
            delay(100)
            it.dispatch(ClientChatAction.Disconnect)
            it.close()
        }

        println("\n  Connecting to room: $roomId...")

        val newStore = createChatStore(roomId)

        // Observe state changes
        collectorJob = launch {
            newStore.state.collect { state ->
                when (val event = state.lastEvent) {
                    is ChatEvent.UserJoined ->
                        println("  * ${event.user} joined [$roomId] (online: ${state.users})")
                    is ChatEvent.UserLeft ->
                        println("  * ${event.user} left [$roomId] (online: ${state.users})")
                    is ChatEvent.MessageReceived ->
                        println("  [$roomId] [${event.user}] ${event.text}")
                    null -> {}
                }
            }
        }

        // Connect and join
        newStore.dispatch(ClientChatAction.SetRoomId(roomId))
        newStore.dispatch(ClientChatAction.SetCurrentUser(username))
        newStore.dispatch(ClientChatAction.Connect)
        delay(500)
        newStore.dispatch(SharedChatAction.JoinRoom(username))
        delay(200)

        println("  Connected to [$roomId] as $username\n")
        currentRoom = roomId

        return newStore
    }

    // Initial connection
    store = connectToRoom(currentRoom)

    // Interactive input loop
    withContext(Dispatchers.IO) {
        while (true) {
            val line = readlnOrNull() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.equals("/quit", ignoreCase = true) -> break

                trimmed.equals("/rooms", ignoreCase = true) -> {
                    println("  Suggested rooms: general, random, kotlin, java, help")
                    println("  (You can join any room name - it will be created automatically)")
                }

                trimmed.equals("/users", ignoreCase = true) -> {
                    val users = store?.currentState?.users ?: emptySet()
                    println("  Users in [$currentRoom]: $users")
                }

                trimmed.equals("/history", ignoreCase = true) -> {
                    val messages = store?.currentState?.messages ?: emptyList()
                    if (messages.isEmpty()) {
                        println("  (no messages in [$currentRoom])")
                    } else {
                        println("  Message history in [$currentRoom]:")
                        messages.forEach { println("    [${it.user}] ${it.text}") }
                    }
                }

                trimmed.startsWith("/join ", ignoreCase = true) -> {
                    val newRoom = trimmed.removePrefix("/join ").trim()
                    if (newRoom.isNotEmpty() && newRoom != currentRoom) {
                        store = connectToRoom(newRoom)
                    } else if (newRoom == currentRoom) {
                        println("  Already in room: $currentRoom")
                    } else {
                        println("  Usage: /join <room>")
                    }
                }

                trimmed.startsWith("/") -> {
                    println("  Unknown command. Type /quit to exit or just type to chat.")
                }

                else -> {
                    store?.dispatch(SharedChatAction.SendMessage(username, trimmed))
                }
            }
        }
    }

    // Cleanup
    println("\n  Leaving room...")
    store?.dispatch(SharedChatAction.LeaveRoom(username))
    delay(300)

    collectorJob?.cancel()
    store?.dispatch(ClientChatAction.Disconnect)
    store?.close()

    println("  Bye!")
}

@Suppress("UNCHECKED_CAST")
private fun createChatStore(roomId: String): Store<ClientChatState, ChatAction> {
    val connection = KtorWebSocketClientConnection.create(
        host = "localhost",
        port = 8080,
        path = "/room/$roomId",
    ).typedJson<SharedChatAction>() as TypedClientConnection<ChatAction>

    return createStore(
        initialState = ClientChatState(roomId = roomId),
        reducer = clientChatReducer,
        middlewares = listOf(ChatRemoteMiddleware(connection)),
    )
}
