package io.flowdux.sample.chat.client

import io.flowdux.ActionProcessorMap
import io.flowdux.remote.ClientRemoteMiddleware
import io.flowdux.remote.TypedClientConnection
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState
import kotlinx.coroutines.CoroutineScope

class ChatRemoteMiddleware(
    connection: TypedClientConnection<ChatAction>,
    scope: CoroutineScope,
) : ClientRemoteMiddleware<ChatState, ChatAction>(
    connection = connection,
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
