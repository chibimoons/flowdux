package io.flowdux.sample.chat.client

import io.flowdux.Reducer
import io.flowdux.buildReducer
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState
import io.flowdux.sample.chat.SharedChatAction

val clientChatReducer: Reducer<ChatState, ChatAction> = buildReducer {
    on<SharedChatAction.SyncState> { _, action -> action.state }
}
