package io.flowdux.sample.chat.server

import io.flowdux.ActionProcessorMap
import io.flowdux.remote.serialization.actionCodecOf
import io.flowdux.remote.server.ServerConnection
import io.flowdux.remote.server.ServerRemoteMiddleware
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState

class ChatServerRemoteMiddleware(
    connection: ServerConnection,
) : ServerRemoteMiddleware<ChatState, ChatAction>(
    connection = connection,
    actionCodec = actionCodecOf(),
) {
    override val name: String = "ChatServerRemoteMiddleware"

    override val processors: ActionProcessorMap<ChatState, ChatAction> = buildProcessors {
        on<ChatAction.StartListening> { _, _ ->
            startListening()
        }
    }
}
