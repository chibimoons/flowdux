package io.flowdux.sample.chat

import io.flowdux.Action
import io.flowdux.Reducer
import io.flowdux.State
import io.flowdux.buildReducer
import io.flowdux.remote.SharedAction

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

sealed interface ChatAction : Action {
    // Lifecycle (local only)
    data object Connect : ChatAction
    data object Disconnect : ChatAction

    // Client → Server (SharedAction)
    data class SendMessage(val user: String, val text: String) : ChatAction, SharedAction
    data class JoinRoom(val user: String) : ChatAction, SharedAction
    data class LeaveRoom(val user: String) : ChatAction, SharedAction

    // Server → Client (local reducer only)
    data class MessageReceived(val user: String, val text: String) : ChatAction
    data class UserJoined(val user: String) : ChatAction
    data class UserLeft(val user: String) : ChatAction
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
