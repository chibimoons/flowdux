package io.flowdux.sample.chat.server

import io.flowdux.remote.server.middleware.SingleClientSyncMiddleware
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.SharedChatAction

class ChatServerRemoteMiddleware(
    connection: TypedServerConnection<ChatAction>,
) : SingleClientSyncMiddleware<ServerChatState, ChatAction>(
    connection = connection,
) {
    override val name: String = "ChatServerRemoteMiddleware"

    override val processors = buildProcessors {
        on<SharedChatAction.SendMessage> { _, action ->
            emit(ServerChatAction.MessageReceived(user = action.user, text = action.text))
        }
        on<SharedChatAction.JoinRoom> { _, action ->
            emit(ServerChatAction.UserJoined(user = action.user))
        }
        on<SharedChatAction.LeaveRoom> { _, action ->
            emit(ServerChatAction.UserLeft(user = action.user))
        }
    }
}
