package io.flowdux.sample.chat.multiroomclient

import io.flowdux.ActionProcessorMap
import io.flowdux.remote.ClientRemoteMiddleware
import io.flowdux.remote.TypedClientConnection
import io.flowdux.sample.chat.ChatAction

class ChatRemoteMiddleware(
    connection: TypedClientConnection<ChatAction>,
) : ClientRemoteMiddleware<ClientChatState, ChatAction>(
    connection = connection,
) {
    override val name: String = "ChatRemoteMiddleware"

    override val processors: ActionProcessorMap<ClientChatState, ChatAction> = buildProcessors {
        on<ClientChatAction.Connect> { _, _ ->
            startConnection()
            emit(ClientChatAction.Connected)
        }
        on<ClientChatAction.Disconnect> { _, _ ->
            stopConnection()
            emit(ClientChatAction.Disconnected)
        }
    }
}
