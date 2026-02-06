package io.flowdux.sample.chat.multiroomclient

import io.flowdux.State
import io.flowdux.sample.chat.ChatEvent
import io.flowdux.sample.chat.ChatMessage

data class ClientChatState(
    val roomId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
    val currentUser: String = "",
    val isConnected: Boolean = false,
) : State
