package io.flowdux.remote.multiplexer

import io.flowdux.Action
import kotlinx.serialization.Serializable

/**
 * A wrapper action that tags an inner action with a roomId for multiplexing.
 *
 * Used by [ServerConnectionMultiplexer] and [ClientConnectionMultiplexer] to
 * route actions to/from specific rooms over a single physical connection.
 *
 * Wire format:
 * ```json
 * {"roomId": "room-1", "action": {"type": "SendMessage", "text": "hello"}}
 * ```
 *
 * @param A The type of the inner action
 * @property roomId The room identifier this action is routed to/from
 * @property action The actual action being transmitted
 */
@Serializable
data class RoutedAction<A : Action>(
    val roomId: String,
    val action: A,
) : Action
