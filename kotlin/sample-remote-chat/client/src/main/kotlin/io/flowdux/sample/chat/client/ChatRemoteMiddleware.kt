package io.flowdux.sample.chat.client

import io.flowdux.ActionProcessorMap
import io.flowdux.remote.RemoteConnection
import io.flowdux.remote.RemoteFlowMiddleware
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState
import kotlinx.coroutines.CoroutineScope

class ChatRemoteMiddleware(
    connection: RemoteConnection,
    scope: CoroutineScope,
) : RemoteFlowMiddleware<ChatState, ChatAction>(
    connection = connection,
    actionCodec = io.flowdux.sample.chat.ChatActionCodec(),
    scope = scope,
) {
    override val name: String = "ChatRemoteMiddleware"

    override val processors: ActionProcessorMap<ChatState, ChatAction> = buildProcessors {
        on<ChatAction.Connect> { _, _ ->
            startConnection()
        }
        on<ChatAction.Disconnect> { _, _ ->
            stopConnection()
        }
    }
}
