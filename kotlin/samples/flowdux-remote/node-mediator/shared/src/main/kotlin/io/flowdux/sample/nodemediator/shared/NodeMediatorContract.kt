package io.flowdux.sample.nodemediator.shared

import io.flowdux.Action
import io.flowdux.State
import io.flowdux.remote.ClientSharedAction
import io.flowdux.remote.ServerSharedAction
import kotlinx.serialization.Serializable

/**
 * Marker interface for all chat actions in the node-mediator sample.
 */
interface ChatAction : Action

/**
 * Shared actions exchanged between client ↔ node ↔ central.
 */
@Serializable
sealed interface SharedChatAction : ChatAction {
    // Client → Server (via Node → Central)
    @Serializable
    data class SendMessage(val user: String, val text: String) : SharedChatAction, ServerSharedAction

    @Serializable
    data class JoinRoom(val user: String) : SharedChatAction, ServerSharedAction

    @Serializable
    data class LeaveRoom(val user: String) : SharedChatAction, ServerSharedAction

    // Server → Client (via Central → Node)
    @Serializable
    data class SyncState(val state: RoomState) : SharedChatAction, ClientSharedAction
}

/**
 * Room state synchronized between server and client.
 */
@Serializable
data class RoomState(
    val roomId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
) : State

@Serializable
data class ChatMessage(
    val user: String,
    val text: String,
)

@Serializable
sealed interface ChatEvent {
    @Serializable
    data class UserJoined(val user: String) : ChatEvent

    @Serializable
    data class UserLeft(val user: String) : ChatEvent

    @Serializable
    data class MessageReceived(val user: String, val text: String) : ChatEvent
}
