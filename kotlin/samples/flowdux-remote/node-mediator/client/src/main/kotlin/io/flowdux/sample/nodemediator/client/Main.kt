package io.flowdux.sample.nodemediator.client

import io.flowdux.ActionProcessorMap
import io.flowdux.Store
import io.flowdux.remote.SyncMiddleware
import io.flowdux.remote.TypedClientConnection
import io.flowdux.remote.createClientStore
import io.flowdux.remote.ktor.KtorWebSocketClientConnection
import io.flowdux.remote.serialization.typedJsonAs
import io.flowdux.sample.nodemediator.shared.ChatAction
import io.flowdux.sample.nodemediator.shared.ChatEvent
import io.flowdux.sample.nodemediator.shared.SharedChatAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Node Mediator Demo — Client
 *
 * Connects to a Node server and participates in a chat room.
 * The client has no awareness of the Central server; it just
 * talks to its local Node.
 *
 * Args: username [nodeHost] [nodePort]
 */
fun main(args: Array<String>) = runBlocking {
    val username = args.getOrElse(0) {
        print("Enter your name: ")
        System.out.flush()
        readlnOrNull()?.trim()?.ifEmpty { null } ?: "User${(1..1000).random()}"
    }
    val nodeHost = args.getOrElse(1) { "localhost" }
    val nodePort = args.getOrElse(2) { "8081" }.toInt()

    println()
    println("=== FlowDux Node Mediator Chat ===")
    println("User: $username")
    println("Node: $nodeHost:$nodePort")
    println("Commands: type a message, /quit to exit")
    println()

    val store = createChatStore(nodeHost, nodePort)

    val collectorJob = launch {
        store.state.collect { state ->
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

private fun createChatStore(host: String, port: Int): Store<ClientChatState, ChatAction> {
    val connection = KtorWebSocketClientConnection.create(
        host = host,
        port = port,
        path = "/ws",
    ).typedJsonAs<SharedChatAction, ChatAction>()

    return createClientStore(
        initialState = ClientChatState(),
        syncMiddleware = ChatRemoteMiddleware(connection),
        reducer = clientChatReducer,
    )
}

private class ChatRemoteMiddleware(
    connection: TypedClientConnection<ChatAction>,
) : SyncMiddleware<ClientChatState, ChatAction>(
    connection = connection,
) {
    override val name: String = "ChatRemoteMiddleware"

    override val processors: ActionProcessorMap<ClientChatState, ChatAction> = buildProcessors {
        on<ClientChatAction.Connect> { _, _ ->
            startConnection()
        }
        on<ClientChatAction.Disconnect> { _, _ ->
            stopConnection()
        }
    }
}
