package io.flowdux.sample.chat.multiroom

import io.flowdux.State
import io.flowdux.sample.chat.ChatEvent
import io.flowdux.sample.chat.ChatMessage

data class ServerChatState(
    val roomId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
    val totalMessagesProcessed: Int = 0,
) : State
