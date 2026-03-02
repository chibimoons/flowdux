package io.flowdux.sample.chat.multiroomclient

import io.flowdux.sample.chat.ChatAction

sealed interface ClientChatAction : ChatAction {
    data object Connect : ClientChatAction

    data object Disconnect : ClientChatAction

    data class SetCurrentUser(val user: String) : ClientChatAction

    data class SetRoomId(val roomId: String) : ClientChatAction

    data object Connected : ClientChatAction

    data object Disconnected : ClientChatAction
}
