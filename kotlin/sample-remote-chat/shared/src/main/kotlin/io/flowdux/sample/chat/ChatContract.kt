package io.flowdux.sample.chat

import io.flowdux.Action
import io.flowdux.Reducer
import io.flowdux.State
import io.flowdux.buildReducer
import io.flowdux.remote.ClientSharedAction
import io.flowdux.remote.ServerSharedAction
import kotlinx.serialization.Serializable

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
) : State

data class ChatMessage(val user: String, val text: String)

sealed interface ChatEvent {
    data class UserJoined(val user: String) : ChatEvent
    data class UserLeft(val user: String) : ChatEvent
    data class MessageReceived(val user: String, val text: String) : ChatEvent
}

@Serializable
sealed interface ChatAction : Action {
    // Lifecycle (local only)
    @Serializable data object Connect : ChatAction
    @Serializable data object Disconnect : ChatAction
    @Serializable data object StartListening : ChatAction

    // Client → Server (intercepted by CRM)
    @Serializable data class SendMessage(val user: String, val text: String) : ChatAction, ServerSharedAction
    @Serializable data class JoinRoom(val user: String) : ChatAction, ServerSharedAction
    @Serializable data class LeaveRoom(val user: String) : ChatAction, ServerSharedAction

    // Server → Client (intercepted by SRM)
    @Serializable data class MessageReceived(val user: String, val text: String) : ChatAction, ClientSharedAction
    @Serializable data class UserJoined(val user: String) : ChatAction, ClientSharedAction
    @Serializable data class UserLeft(val user: String) : ChatAction, ClientSharedAction
}

val chatReducer: Reducer<ChatState, ChatAction> = buildReducer {
    on<ChatAction.MessageReceived> { state, action ->
        state.copy(
            messages = state.messages + ChatMessage(action.user, action.text),
            lastEvent = ChatEvent.MessageReceived(user = action.user, text = action.text),
        )
    }
    on<ChatAction.UserJoined> { state, action ->
        state.copy(
            users = state.users + action.user,
            lastEvent = ChatEvent.UserJoined(user = action.user),
        )
    }
    on<ChatAction.UserLeft> { state, action ->
        state.copy(
            users = state.users - action.user,
            lastEvent = ChatEvent.UserLeft(user = action.user),
        )
    }
}
