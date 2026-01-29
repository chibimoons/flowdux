package io.flowdux.sample.chat.server

import io.flowdux.ActionProcessorMap
import io.flowdux.remote.server.ServerRemoteMiddleware
import io.flowdux.remote.server.TypedServerConnection
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState

class ChatServerRemoteMiddleware(
    connection: TypedServerConnection<ChatAction>,
) : ServerRemoteMiddleware<ChatState, ChatAction>(
    connection = connection,
) {
    override val name: String = "ChatServerRemoteMiddleware"

    override val processors: ActionProcessorMap<ChatState, ChatAction> = buildProcessors {
        on<ChatAction.StartListening> { _, _ ->
            startListening()
        }
        on<ChatAction.SendMessage> { _, action ->
            emit(ChatAction.MessageReceived(user = action.user, text = action.text))
        }
        on<ChatAction.JoinRoom> { _, action ->
            emit(ChatAction.UserJoined(user = action.user))
        }
        on<ChatAction.LeaveRoom> { _, action ->
            emit(ChatAction.UserLeft(user = action.user))
        }
    }
}
