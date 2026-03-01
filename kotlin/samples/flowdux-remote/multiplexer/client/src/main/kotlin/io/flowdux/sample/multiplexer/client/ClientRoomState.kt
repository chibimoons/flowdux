package io.flowdux.sample.multiplexer.client

import io.flowdux.State
import io.flowdux.buildReducer
import io.flowdux.sample.multiplexer.ChatAction
import io.flowdux.sample.multiplexer.ChatEvent
import io.flowdux.sample.multiplexer.ChatMessage
import io.flowdux.sample.multiplexer.SharedChatAction

/**
 * Client-side state for a single room.
 */
data class ClientRoomState(
    val roomId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
    val isConnected: Boolean = false,
) : State

/**
 * Client-only actions for local state management.
 */
sealed interface ClientRoomAction : ChatAction {
    data object Connect : ClientRoomAction

    data object Disconnect : ClientRoomAction
}

/**
 * Client-side reducer for room state.
 */
val clientRoomReducer =
    buildReducer<ClientRoomState, ChatAction> {
        on<SharedChatAction.SyncState> { state, action ->
            state.copy(
                roomId = action.state.roomId,
                messages = action.state.messages,
                users = action.state.users,
                lastEvent = action.state.lastEvent,
            )
        }

        on<ClientRoomAction.Connect> { state, _ ->
            state.copy(isConnected = true)
        }

        on<ClientRoomAction.Disconnect> { state, _ ->
            state.copy(isConnected = false)
        }
    }
