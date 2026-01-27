package io.flowdux.sample.chat.server

import io.flowdux.ActionProcessorMap
import io.flowdux.Middleware
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState

class ChatServerMiddleware : Middleware<ChatState, ChatAction> {

    override val name: String = "ChatServerMiddleware"

    override val processors: ActionProcessorMap<ChatState, ChatAction> = buildProcessors {
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
