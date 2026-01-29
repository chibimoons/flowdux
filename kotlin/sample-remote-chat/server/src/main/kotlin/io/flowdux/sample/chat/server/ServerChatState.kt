package io.flowdux.sample.chat.server

import io.flowdux.State
import io.flowdux.sample.chat.ChatEvent
import io.flowdux.sample.chat.ChatMessage

data class ServerChatState(
    // Synced to client (mapped to ChatState)
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,

    // Server-local
    val totalMessagesProcessed: Int = 0,
    val isListening: Boolean = false,
) : State
