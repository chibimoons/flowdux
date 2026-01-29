package io.flowdux.remote.serialization

import io.flowdux.Action
import io.flowdux.remote.ClientConnection
import io.flowdux.remote.TypedClientConnection
import io.flowdux.remote.server.ServerConnection
import io.flowdux.remote.server.TypedServerConnection
import io.flowdux.remote.server.typed
import io.flowdux.remote.typed
import kotlinx.serialization.json.Json

/**
 * Creates a [TypedClientConnection] using JSON serialization.
 *
 * Convenience shortcut that combines [actionCodecOf] and [JsonMessageCodec]:
 * ```kotlin
 * // Before
 * val typed = connection.typed(actionCodecOf<MyAction>(), JsonMessageCodec())
 *
 * // After
 * val typed = connection.typedJson<MyAction>()
 * ```
 *
 * @param json Custom [Json] instance for action serialization. Defaults to
 *   [SerializableActionCodec.DefaultJson] (with `classDiscriminator = "type"`).
 */
inline fun <reified A : Action> ClientConnection.typedJson(
    json: Json = SerializableActionCodec.DefaultJson,
): TypedClientConnection<A> = typed(actionCodecOf<A>(json), JsonMessageCodec())

/**
 * Creates a [TypedServerConnection] using JSON serialization.
 *
 * Convenience shortcut that combines [actionCodecOf] and [JsonMessageCodec]:
 * ```kotlin
 * // Before
 * val typed = connection.typed(actionCodecOf<MyAction>(), JsonMessageCodec())
 *
 * // After
 * val typed = connection.typedJson<MyAction>()
 * ```
 *
 * @param json Custom [Json] instance for action serialization. Defaults to
 *   [SerializableActionCodec.DefaultJson] (with `classDiscriminator = "type"`).
 */
inline fun <reified A : Action> ServerConnection.typedJson(
    json: Json = SerializableActionCodec.DefaultJson,
): TypedServerConnection<A> = typed(actionCodecOf<A>(json), JsonMessageCodec())
