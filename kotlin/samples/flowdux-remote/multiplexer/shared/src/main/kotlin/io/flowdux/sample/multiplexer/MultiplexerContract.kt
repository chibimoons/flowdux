package io.flowdux.sample.multiplexer

import io.flowdux.Action
import io.flowdux.State
import io.flowdux.remote.ClientSharedAction
import io.flowdux.remote.ServerSharedAction
import kotlinx.serialization.Serializable

/**
 * Base action interface for the multiplexer chat sample.
 *
 * This sample demonstrates how a single WebSocket connection can be used
 * to participate in multiple chat rooms simultaneously using ConnectionMultiplexer.
 */
interface ChatAction : Action

/**
 * Actions that cross the wire between client and server.
 * These are wrapped in RoutedAction by the multiplexer for room-based routing.
 */
@Serializable
sealed interface SharedChatAction : ChatAction {
    // Client → Server
    @Serializable
    data class SendMessage(val user: String, val text: String) : SharedChatAction, ServerSharedAction

    @Serializable
    data class JoinRoom(val user: String) : SharedChatAction, ServerSharedAction

    @Serializable
    data class LeaveRoom(val user: String) : SharedChatAction, ServerSharedAction

    // Server → Client
    @Serializable
    data class SyncState(val state: RoomState) : SharedChatAction, ClientSharedAction
}

/**
 * State of a single chat room.
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
