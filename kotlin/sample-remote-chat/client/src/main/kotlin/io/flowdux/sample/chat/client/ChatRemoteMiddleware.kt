package io.flowdux.sample.chat.client

import io.flowdux.ActionProcessorMap
import io.flowdux.Store
import io.flowdux.remote.RemoteFlowMiddleware
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatActionCodec
import io.flowdux.sample.chat.ChatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ChatRemoteMiddleware(
    private val wsConnection: WebSocketConnection,
    scope: CoroutineScope,
) : RemoteFlowMiddleware<ChatState, ChatAction>(
    connection = wsConnection,
    actionCodec = ChatActionCodec(),
    scope = scope,
) {
    internal lateinit var store: Store<ChatState, ChatAction>

    override val name: String = "ChatRemoteMiddleware"

    override val processors: ActionProcessorMap<ChatState, ChatAction> = buildProcessors {
        on<ChatAction.Connect> { _, _ ->
            connectTo(store)
        }
        on<ChatAction.Disconnect> { _, _ ->
            wsConnection.disconnect()
        }
    }

    override fun process(getState: () -> ChatState, action: ChatAction): Flow<ChatAction> {
        val processor = processors[action::class]
        if (processor != null) {
            return flow { processor.invoke(this, getState(), action) }
        }
        return super.process(getState, action)
    }
}
