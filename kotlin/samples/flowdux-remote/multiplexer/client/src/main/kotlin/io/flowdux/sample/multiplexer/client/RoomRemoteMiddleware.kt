package io.flowdux.sample.multiplexer.client

import io.flowdux.remote.SyncMiddleware
import io.flowdux.remote.TypedClientConnection
import io.flowdux.sample.multiplexer.ChatAction

/**
 * Remote middleware for a single room connection.
 *
 * Each room has its own middleware instance that handles communication
 * through its virtual connection from the multiplexer.
 */
class RoomRemoteMiddleware(connection: TypedClientConnection<ChatAction>) :
    SyncMiddleware<ClientRoomState, ChatAction>(
        connection = connection,
    ) {
    override val processors =
        buildProcessors {
            on<ClientRoomAction.Connect> { _, _ ->
                startConnection()
            }

            on<ClientRoomAction.Disconnect> { _, _ ->
                stopConnection()
            }
        }
}
