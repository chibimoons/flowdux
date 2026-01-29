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
    }
}
