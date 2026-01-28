package io.flowdux.sample.chat.client

import io.flowdux.ActionProcessorMap
import io.flowdux.remote.RemoteFlowMiddleware
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState
import kotlinx.coroutines.CoroutineScope

class ChatRemoteMiddleware(
    wsConnection: WebSocketConnection,
    scope: CoroutineScope,
) : RemoteFlowMiddleware<ChatState, ChatAction>(
    connection = wsConnection,
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
