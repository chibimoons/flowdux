package io.flowdux.sample.chat.server

import io.flowdux.ActionProcessorMap
import io.flowdux.remote.server.ServerRemoteMiddleware
import io.flowdux.remote.server.TypedServerConnection
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState
import io.flowdux.sample.chat.SharedChatAction

class ChatServerRemoteMiddleware(
    connection: TypedServerConnection<ChatAction>,
) : ServerRemoteMiddleware<ChatState, ChatAction>(
    connection = connection,
) {
    override val name: String = "ChatServerRemoteMiddleware"

    override val processors: ActionProcessorMap<ChatState, ChatAction> = buildProcessors {
        on<ServerChatAction.StartListening> { _, _ ->
            startListening()
        }
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
