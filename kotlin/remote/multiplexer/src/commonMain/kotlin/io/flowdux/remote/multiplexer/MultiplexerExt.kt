package io.flowdux.remote.multiplexer

import io.flowdux.Action
import io.flowdux.remote.ClientConnection
import io.flowdux.remote.TypedClientConnection
import io.flowdux.remote.serialization.JsonMessageCodec
import io.flowdux.remote.serialization.SerializableActionCodec
import io.flowdux.remote.serialization.actionCodecOf
import io.flowdux.remote.server.connection.ServerConnection
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.typed
import io.flowdux.remote.typed
import kotlinx.serialization.json.Json

/**
 * Creates a [TypedClientConnection] for [RoutedAction] using JSON serialization.
 *
 * Use this when creating multiplexed connections:
 * ```kotlin
 * val physical = KtorWebSocketClientConnection.create(host, port, "/ws")
 *     .typedRoutedJson<ChatAction>()
 *
 * val mux = ClientConnectionMultiplexer(physical, scope)
 * ```
 *
 * @param json Custom [Json] instance for action serialization. Defaults to
 *   [SerializableActionCodec.DefaultJson] (with `classDiscriminator = "type"`).
 */
inline fun <reified A : Action> ClientConnection.typedRoutedJson(
    json: Json = SerializableActionCodec.DefaultJson,
): TypedClientConnection<RoutedAction<A>> {
    val routedCodec = actionCodecOf<RoutedAction<A>>(json)
    return typed(routedCodec, JsonMessageCodec())
}

/**
 * Creates a [TypedServerConnection] for [RoutedAction] using JSON serialization.
 *
 * Use this when creating multiplexed connections:
 * ```kotlin
 * webSocket("/ws") {
 *     val physical = KtorWebSocketServerConnection(this)
 *         .typedRoutedJson<ChatAction>()
 *
 *     val mux = ServerConnectionMultiplexer(physical, this)
 * }
 * ```
 *
 * @param json Custom [Json] instance for action serialization. Defaults to
 *   [SerializableActionCodec.DefaultJson] (with `classDiscriminator = "type"`).
 */
inline fun <reified A : Action> ServerConnection.typedRoutedJson(
    json: Json = SerializableActionCodec.DefaultJson,
): TypedServerConnection<RoutedAction<A>> {
    val routedCodec = actionCodecOf<RoutedAction<A>>(json)
    return typed(routedCodec, JsonMessageCodec())
}
