package io.flowdux.sample.poker.client

import io.flowdux.ActionProcessorMap
import io.flowdux.remote.SyncMiddleware
import io.flowdux.remote.TypedClientConnection
import io.flowdux.sample.poker.PokerAction

class PokerRemoteMiddleware(connection: TypedClientConnection<PokerAction>) :
    SyncMiddleware<ClientPokerState, PokerAction>(
        connection = connection,
    ) {
    override val processors: ActionProcessorMap<ClientPokerState, PokerAction> =
        buildProcessors {
            on<ClientPokerAction.Connect> { _, _ ->
                startConnection()
            }
            on<ClientPokerAction.Disconnect> { _, _ ->
                stopConnection()
            }
        }
}
