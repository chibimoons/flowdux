package io.flowdux.remote.nodemediator

import io.flowdux.Action
import kotlinx.serialization.Serializable

/**
 * A wrapper action that tags an inner action with a roomId for node-level routing.
 *
 * Used by [NodeMediator] and [CentralNodeManager] to route actions between
 * the Central Store and individual Node servers.
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
data class NodeAction<A : Action>(val roomId: String, val action: A) : Action
