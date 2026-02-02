package io.flowdux.sample.chat.web

import io.flowdux.ActionProcessorMap
import io.flowdux.State
import io.flowdux.createStore
import io.flowdux.buildReducer
import io.flowdux.remote.ClientRemoteMiddleware
import io.flowdux.remote.TypedClientConnection
import io.flowdux.remote.serialization.typedJson
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatMessage
import io.flowdux.sample.chat.ChatEvent
import io.flowdux.sample.chat.SharedChatAction
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

// ── Local-only actions ──

sealed interface ClientChatAction : ChatAction {
    data object Connect : ClientChatAction
    data object Disconnect : ClientChatAction
    data class SetCurrentUser(val user: String) : ClientChatAction
}

// ── State ──

data class ClientChatState(
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
    val currentUser: String = "",
) : State

// ── Reducer ──

val clientChatReducer = buildReducer<ClientChatState, ChatAction> {
    on<SharedChatAction.SyncState> { state, action ->
        state.copy(
            messages = action.state.messages,
            users = action.state.users,
            lastEvent = action.state.lastEvent,
        )
    }
    on<ClientChatAction.SetCurrentUser> { state, action ->
        state.copy(currentUser = action.user)
    }
}

// ── Middleware ──

class ChatRemoteMiddleware(
    connection: TypedClientConnection<ChatAction>,
) : ClientRemoteMiddleware<ClientChatState, ChatAction>(connection) {

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

// ── UI ──

private val scope = MainScope()

fun main() {
    // DOM elements — Login screen
    val loginScreen = document.getElementById("login-screen") as HTMLDivElement
    val chatScreen = document.getElementById("chat-screen") as HTMLDivElement
    val usernameInput = document.getElementById("username-input") as HTMLInputElement
    val joinBtn = document.getElementById("join-btn") as HTMLButtonElement

    // DOM elements — Chat screen
    val messagesDiv = document.getElementById("messages") as HTMLDivElement
    val messageInput = document.getElementById("message-input") as HTMLInputElement
    val sendBtn = document.getElementById("send-btn") as HTMLButtonElement
    val usersList = document.getElementById("users-list") as HTMLElement
    val currentUserLabel = document.getElementById("current-user") as HTMLElement
    val leaveBtn = document.getElementById("leave-btn") as HTMLButtonElement

    // Create store (lazily after user joins)
    var storeReady = false

    @Suppress("UNCHECKED_CAST")
    val connection = BrowserWebSocketClientConnection("ws://localhost:8080/chat")
        .typedJson<SharedChatAction>() as TypedClientConnection<ChatAction>

    val store = createStore(
        initialState = ClientChatState(),
        reducer = clientChatReducer,
        middlewares = listOf(ChatRemoteMiddleware(connection)),
        scope = scope,
    )

    fun renderMessages(messages: List<ChatMessage>, currentUser: String) {
        messagesDiv.innerHTML = ""
        for (msg in messages) {
            val div = document.createElement("div") as HTMLDivElement
            val isMe = msg.user == currentUser
            div.className = if (isMe) "message message-mine" else "message"
            div.innerHTML = "<span class=\"message-user\">${escapeHtml(msg.user)}</span> " +
                "<span class=\"message-text\">${escapeHtml(msg.text)}</span>"
            messagesDiv.appendChild(div)
        }
        messagesDiv.scrollTop = messagesDiv.scrollHeight.toDouble()
    }

    fun renderUsers(users: Set<String>) {
        usersList.innerHTML = ""
        for (user in users.sorted()) {
            val li = document.createElement("li")
            li.textContent = user
            usersList.appendChild(li)
        }
    }

    fun renderEvent(event: ChatEvent?) {
        if (event == null) return
        val div = document.createElement("div") as HTMLDivElement
        div.className = "system-message"
        div.textContent = when (event) {
            is ChatEvent.UserJoined -> "${event.user} joined the chat"
            is ChatEvent.UserLeft -> "${event.user} left the chat"
            is ChatEvent.MessageReceived -> return // handled by messages list
        }
        messagesDiv.appendChild(div)
        messagesDiv.scrollTop = messagesDiv.scrollHeight.toDouble()
    }

    // State collector
    scope.launch {
        store.state.collect { state ->
            if (!storeReady) return@collect
            renderMessages(state.messages, state.currentUser)
            renderUsers(state.users)
            renderEvent(state.lastEvent)
        }
    }

    // Join handler
    fun join() {
        val username = usernameInput.value.trim()
        if (username.isEmpty()) return

        storeReady = true
        currentUserLabel.textContent = username
        loginScreen.style.display = "none"
        chatScreen.style.display = "flex"

        store.dispatch(ClientChatAction.SetCurrentUser(username))
        store.dispatch(ClientChatAction.Connect)

        // Delay JoinRoom slightly to allow WebSocket to connect
        scope.launch {
            kotlinx.coroutines.delay(500)
            store.dispatch(SharedChatAction.JoinRoom(username))
        }

        messageInput.focus()
    }

    // Send message handler
    fun sendMessage() {
        val text = messageInput.value.trim()
        if (text.isEmpty()) return
        val user = store.currentState.currentUser
        store.dispatch(SharedChatAction.SendMessage(user, text))
        messageInput.value = ""
        messageInput.focus()
    }

    // Event listeners
    joinBtn.onclick = { join(); Unit }
    usernameInput.onkeydown = { e ->
        if (e.key == "Enter") join()
        Unit
    }

    sendBtn.onclick = { sendMessage(); Unit }
    messageInput.onkeydown = { e ->
        if (e.key == "Enter") sendMessage()
        Unit
    }

    leaveBtn.onclick = {
        val user = store.currentState.currentUser
        store.dispatch(SharedChatAction.LeaveRoom(user))
        scope.launch {
            kotlinx.coroutines.delay(300)
            store.dispatch(ClientChatAction.Disconnect)
            storeReady = false
            chatScreen.style.display = "none"
            loginScreen.style.display = "flex"
            usernameInput.value = ""
            usernameInput.focus()
        }
        Unit
    }
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
