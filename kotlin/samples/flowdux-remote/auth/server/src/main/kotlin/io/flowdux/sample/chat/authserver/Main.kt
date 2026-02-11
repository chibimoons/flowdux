package io.flowdux.sample.chat.authserver

import io.flowdux.Middleware
import io.flowdux.remote.auth.server.AuthResult
import io.flowdux.remote.auth.server.AuthVerifier
import io.flowdux.remote.auth.server.withAuth
import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.serialization.typedJsonAs
import io.flowdux.remote.server.pattern.createSharedStateServer
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

/**
 * Demo token verifier.
 * Valid token format: "user:{name}" — extracts the name as both userId and displayName.
 */
private val tokenVerifier = AuthVerifier<ChatPrincipal> { token ->
    if (token.startsWith("user:")) {
        val name = token.removePrefix("user:")
        AuthResult.Success(ChatPrincipal(userId = name, displayName = name))
    } else {
        AuthResult.Failure("Invalid token format. Expected 'user:{name}'")
    }
}

fun main() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val server = createSharedStateServer(
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

    applicationScope.launch {
        server.state.collect { state ->
            println(
                "[Server] clients=${runCatching { server.sessionCount() }.getOrDefault(0)}, " +
                    "users=${state.users}, messages=${state.totalMessagesProcessed}",
            )
        }
    }

    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            webSocket("/chat") {
                val authed = KtorWebSocketServerConnection(this)
                    .withAuth(tokenVerifier)

                when (val result = authed.awaitAuth(this)) {
                    is AuthResult.Success -> {
                        val sessionId = result.principal.userId
                        println("[Server] Authenticated: ${result.principal.displayName} ($sessionId)")

                        val connection = authed
                            .typedJsonAs<SharedChatAction, ChatAction>()

                        try {
                            server.handleClient(sessionId, connection)
                        } finally {
                            println("[Server] Disconnected: $sessionId")
                        }
                    }

                    is AuthResult.Failure -> {
                        println("[Server] Auth failed: ${result.reason}")
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, result.reason))
                    }
                }
            }
        }
    }.start(wait = true)

    server.close()
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
