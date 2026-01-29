package io.flowdux.sample.chat.server

import io.flowdux.Reducer
import io.flowdux.buildReducer
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatEvent
import io.flowdux.sample.chat.ChatMessage
import io.flowdux.sample.chat.ChatState

val serverChatReducer: Reducer<ChatState, ChatAction> = buildReducer {
    on<ServerChatAction.MessageReceived> { state, action ->
        state.copy(
            messages = state.messages + ChatMessage(action.user, action.text),
            lastEvent = ChatEvent.MessageReceived(user = action.user, text = action.text),
        )
    }
    on<ServerChatAction.UserJoined> { state, action ->
        state.copy(
            users = state.users + action.user,
            lastEvent = ChatEvent.UserJoined(user = action.user),
        )
    }
    on<ServerChatAction.UserLeft> { state, action ->
        state.copy(
            users = state.users - action.user,
            lastEvent = ChatEvent.UserLeft(user = action.user),
        )
    }
}
