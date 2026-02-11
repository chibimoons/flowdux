package io.flowdux.sample.chat.authclient

import io.flowdux.ActionProcessorMap
import io.flowdux.remote.SyncMiddleware
import io.flowdux.remote.TypedClientConnection
import io.flowdux.sample.chat.ChatAction

class ChatRemoteMiddleware(
    connection: TypedClientConnection<ChatAction>,
) : SyncMiddleware<ClientChatState, ChatAction>(
    connection = connection,
) {
    override val name: String = "ChatRemoteMiddleware"

    override val processors: ActionProcessorMap<ClientChatState, ChatAction> = buildProcessors {
        on<ClientChatAction.Connect> { _, _ ->
            startConnection()
        }
        on<ClientChatAction.Disconnect> { _, _ ->
            stopConnection()
        }
    }
}
