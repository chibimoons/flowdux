package io.flowdux.sample.chat.client

import io.flowdux.ActionProcessorMap
import io.flowdux.remote.ClientRemoteMiddleware
import io.flowdux.remote.TypedClientConnection
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState

class ChatRemoteMiddleware(
    connection: TypedClientConnection<ChatAction>,
) : ClientRemoteMiddleware<ChatState, ChatAction>(
    connection = connection,
) {
    override val name: String = "ChatRemoteMiddleware"

    override val processors: ActionProcessorMap<ChatState, ChatAction> = buildProcessors {
        on<ClientChatAction.Connect> { _, _ ->
            startConnection()
        }
        on<ClientChatAction.Disconnect> { _, _ ->
            stopConnection()
        }
    }
}
