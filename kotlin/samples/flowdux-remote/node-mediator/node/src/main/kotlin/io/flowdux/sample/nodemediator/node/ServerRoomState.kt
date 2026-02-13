package io.flowdux.sample.nodemediator.node

import io.flowdux.State
import io.flowdux.buildReducer
import io.flowdux.sample.nodemediator.shared.ChatAction
import io.flowdux.sample.nodemediator.shared.ChatEvent
import io.flowdux.sample.nodemediator.shared.ChatMessage

/**
 * Server-side room state with metadata.
 */
data class ServerRoomState(
    val roomId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
    val totalMessagesProcessed: Int = 0,
) : State

/**
 * Server-only actions for internal state updates.
 */
sealed interface ServerRoomAction : ChatAction {
    data class MessageReceived(val user: String, val text: String) : ServerRoomAction
    data class UserJoined(val user: String) : ServerRoomAction
    data class UserLeft(val user: String) : ServerRoomAction
}

/**
 * Server-side reducer for room state.
 */
val serverRoomReducer = buildReducer<ServerRoomState, ChatAction> {
    on<ServerRoomAction.MessageReceived> { state, action ->
        state.copy(
            messages = state.messages + ChatMessage(action.user, action.text),
            lastEvent = ChatEvent.MessageReceived(user = action.user, text = action.text),
            totalMessagesProcessed = state.totalMessagesProcessed + 1,
        )
    }

    on<ServerRoomAction.UserJoined> { state, action ->
        state.copy(
            users = state.users + action.user,
            lastEvent = ChatEvent.UserJoined(user = action.user),
        )
    }

    on<ServerRoomAction.UserLeft> { state, action ->
        state.copy(
            users = state.users - action.user,
            lastEvent = ChatEvent.UserLeft(user = action.user),
        )
    }
}
