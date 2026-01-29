package io.flowdux.sample.chat.client

import io.flowdux.sample.chat.ChatAction

sealed interface ClientChatAction : ChatAction {
    data object Connect : ClientChatAction
    data object Disconnect : ClientChatAction
}
