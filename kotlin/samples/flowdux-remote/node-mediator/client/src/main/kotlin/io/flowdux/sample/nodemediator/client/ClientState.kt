package io.flowdux.sample.nodemediator.client

import io.flowdux.State
import io.flowdux.buildReducer
import io.flowdux.sample.nodemediator.shared.ChatAction
import io.flowdux.sample.nodemediator.shared.ChatEvent
import io.flowdux.sample.nodemediator.shared.ChatMessage
import io.flowdux.sample.nodemediator.shared.SharedChatAction

/**
 * Client-side chat state.
 */
data class ClientChatState(
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
    val currentUser: String = "",
    val currentRoom: String = "",
) : State

/**
 * Client-only actions.
 */
sealed interface ClientChatAction : ChatAction {
    data object Connect : ClientChatAction

    data object Disconnect : ClientChatAction

    data class SetCurrentUser(val user: String) : ClientChatAction
}

/**
 * Client-side reducer.
 */
val clientChatReducer =
    buildReducer<ClientChatState, ChatAction> {
        on<SharedChatAction.SyncState> { state, action ->
            state.copy(
                messages = action.state.messages,
                users = action.state.users,
                lastEvent = action.state.lastEvent,
                currentRoom = action.state.roomId,
            )
        }

        on<ClientChatAction.SetCurrentUser> { state, action ->
            state.copy(currentUser = action.user)
        }
    }
