package io.flowdux.sample.chat.multiclient

import io.flowdux.sample.chat.ChatAction

sealed interface ClientChatAction : ChatAction {
    data object Connect : ClientChatAction

    data object Disconnect : ClientChatAction

    data class SetCurrentUser(val user: String) : ClientChatAction
}
