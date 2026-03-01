package io.flowdux.sample.chat.client

import io.flowdux.State
import io.flowdux.sample.chat.ChatEvent
import io.flowdux.sample.chat.ChatMessage

data class ClientChatState(
    // Synced from server (selective)
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
    // Client-local
    val currentUser: String = "",
) : State
