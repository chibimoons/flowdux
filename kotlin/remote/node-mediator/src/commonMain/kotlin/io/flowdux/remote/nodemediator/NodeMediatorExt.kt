package io.flowdux.remote.nodemediator

import io.flowdux.Action
import io.flowdux.remote.ClientConnection
import io.flowdux.remote.TypedClientConnection
import io.flowdux.remote.serialization.JsonMessageCodec
import io.flowdux.remote.serialization.SerializableActionCodec
import io.flowdux.remote.server.connection.ServerConnection
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.typed
import io.flowdux.remote.typed
import kotlinx.serialization.json.Json

/**
 * Creates a [TypedClientConnection] for [NodeAction] using JSON serialization.
 *
 * Use this when creating node-to-central connections:
 * ```kotlin
 * val physical = KtorWebSocketClientConnection.create(host, port, "/node")
 *     .typedNodeActionJson<SharedAction>()
 *
 * val mediator = NodeMediator(nodeId, physical, scope)
 * ```
 *
 * @param json Custom [Json] instance for action serialization. Defaults to
 *   [SerializableActionCodec.DefaultJson] (with `classDiscriminator = "type"`).
 */
inline fun <reified A : Action> ClientConnection.typedNodeActionJson(
    json: Json = SerializableActionCodec.DefaultJson,
): TypedClientConnection<NodeAction<A>> {
    val nodeCodec = nodeRoutedActionCodecOf<A>(json)
    return typed(nodeCodec, JsonMessageCodec())
}

/**
 * Creates a [TypedServerConnection] for [NodeAction] using JSON serialization.
 *
 * Use this when accepting node connections on the central server:
 * ```kotlin
 * webSocket("/node") {
 *     val physical = KtorWebSocketServerConnection(this)
 *         .typedNodeActionJson<SharedAction>()
 *
 *     centralNodeManager.handleNode(nodeId, physical)
 * }
 * ```
 *
 * @param json Custom [Json] instance for action serialization. Defaults to
 *   [SerializableActionCodec.DefaultJson] (with `classDiscriminator = "type"`).
 */
inline fun <reified A : Action> ServerConnection.typedNodeActionJson(
    json: Json = SerializableActionCodec.DefaultJson,
): TypedServerConnection<NodeAction<A>> {
    val nodeCodec = nodeRoutedActionCodecOf<A>(json)
    return typed(nodeCodec, JsonMessageCodec())
}
