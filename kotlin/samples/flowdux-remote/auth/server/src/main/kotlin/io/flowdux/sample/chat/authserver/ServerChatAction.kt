package io.flowdux.sample.chat.authserver

import io.flowdux.sample.chat.ChatAction

sealed interface ServerChatAction : ChatAction {
    data class MessageReceived(val user: String, val text: String) : ServerChatAction
    data class UserJoined(val user: String) : ServerChatAction
    data class UserLeft(val user: String) : ServerChatAction
}
