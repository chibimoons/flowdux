package io.flowdux.sample.nodemediator.node

import io.flowdux.State
import io.flowdux.buildReducer
import io.flowdux.sample.nodemediator.shared.ChatEvent
import io.flowdux.sample.nodemediator.shared.ChatMessage
import io.flowdux.sample.nodemediator.shared.SharedChatAction

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
 * Server-side reducer for room state.
 * Handles SharedChatAction directly without intermediate server-only actions.
 */
val serverRoomReducer =
    buildReducer<ServerRoomState, SharedChatAction> {
        on<SharedChatAction.SendMessage> { state, action ->
            state.copy(
                messages = state.messages + ChatMessage(action.user, action.text),
                lastEvent = ChatEvent.MessageReceived(user = action.user, text = action.text),
                totalMessagesProcessed = state.totalMessagesProcessed + 1,
            )
        }

        on<SharedChatAction.JoinRoom> { state, action ->
            state.copy(
                users = state.users + action.user,
                lastEvent = ChatEvent.UserJoined(user = action.user),
            )
        }

        on<SharedChatAction.LeaveRoom> { state, action ->
            state.copy(
                users = state.users - action.user,
                lastEvent = ChatEvent.UserLeft(user = action.user),
            )
        }
    }
