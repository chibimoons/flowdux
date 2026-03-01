package io.flowdux.remote.serialization

import io.flowdux.Action
import io.flowdux.remote.ActionCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * [ActionCodec] implementation backed by `kotlinx.serialization`.
 *
 * Configures `classDiscriminator = "type"` by default so the discriminator
 * **field name** matches the manual codec convention (`{"type": "...", ...}`).
 * Note that the discriminator **value** is determined by `kotlinx.serialization`
 * (the serial name of each subclass, which defaults to the qualified class name).
 * To use short names like `{"type":"SendMessage", ...}`, annotate subclasses
 * with `@SerialName("SendMessage")`.
 *
 * ## Forward compatibility
 *
 * [DefaultJson] enables `ignoreUnknownKeys`, so actions serialized by a newer
 * schema version (with additional fields) can still be decoded by an older version.
 * Unknown type discriminators (entirely new action subclasses) will still throw
 * [kotlinx.serialization.SerializationException] — use [io.flowdux.remote.decodeOrNull] or the
 * `onDecodeError` callback on typed connections to handle those gracefully.
 *
 * Usage:
 * ```kotlin
 * val codec = actionCodecOf<ChatAction>()
 * ```
 */
class SerializableActionCodec<A : Action>(
    private val serializer: KSerializer<A>,
    private val json: Json = DefaultJson,
) : ActionCodec<A> {

    override fun encode(action: A): String = json.encodeToString(serializer, action)

    override fun decode(json: String): A = this.json.decodeFromString(serializer, json)

    companion object {
        val DefaultJson: Json = Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
        }
    }
}

/**
 * Creates an [ActionCodec] for the reified action type [A] using `kotlinx.serialization`.
 *
 * The type parameter is inferred from context, so explicit specification is usually unnecessary:
 * ```kotlin
 * val typedConnection = rawConnection.typed(actionCodecOf<MyAction>(), JsonMessageCodec())
 * val middleware = MySyncMiddleware(typedConnection)
 * ```
 */
inline fun <reified A : Action> actionCodecOf(
    json: Json = SerializableActionCodec.DefaultJson,
): ActionCodec<A> = SerializableActionCodec(serializer(), json)
