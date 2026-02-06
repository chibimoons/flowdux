package io.flowdux.sample.poker.client

import io.flowdux.ActionProcessorMap
import io.flowdux.remote.ClientRemoteMiddleware
import io.flowdux.remote.TypedClientConnection
import io.flowdux.sample.poker.PokerAction

class PokerRemoteMiddleware(
    connection: TypedClientConnection<PokerAction>,
) : ClientRemoteMiddleware<ClientPokerState, PokerAction>(
    connection = connection,
) {
    override val name: String = "PokerRemoteMiddleware"

    override val processors: ActionProcessorMap<ClientPokerState, PokerAction> = buildProcessors {
        on<ClientPokerAction.Connect> { _, _ ->
            startConnection()
        }
        on<ClientPokerAction.Disconnect> { _, _ ->
            stopConnection()
        }
    }
}
