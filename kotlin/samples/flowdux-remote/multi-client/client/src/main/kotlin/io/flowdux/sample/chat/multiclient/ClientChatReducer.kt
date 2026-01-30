package io.flowdux.sample.chat.multiclient

import io.flowdux.Reducer
import io.flowdux.buildReducer
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.SharedChatAction

val clientChatReducer: Reducer<ClientChatState, ChatAction> = buildReducer {
    on<SharedChatAction.SyncState> { state, action ->
        state.copy(
            messages = action.state.messages,
            users = action.state.users,
            lastEvent = action.state.lastEvent,
        )
    }
    on<ClientChatAction.SetCurrentUser> { state, action ->
        state.copy(currentUser = action.user)
    }
}
