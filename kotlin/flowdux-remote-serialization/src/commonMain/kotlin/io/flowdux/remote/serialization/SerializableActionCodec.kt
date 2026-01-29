package io.flowdux.remote.serialization

import io.flowdux.Action
import io.flowdux.remote.ActionCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * [ActionCodec] implementation backed by `kotlinx.serialization`.
 *
 * Uses `classDiscriminator = "type"` by default so the wire format matches
 * the manual codec convention (`{"type":"ActionName", ...}`).
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
        }
    }
}

/**
 * Creates an [ActionCodec] for the reified action type [A] using `kotlinx.serialization`.
 *
 * The type parameter is inferred from context, so explicit specification is usually unnecessary:
 * ```kotlin
 * val typedConnection = rawConnection.typed(actionCodecOf<MyAction>())
 * val srm = MyServerRemoteMiddleware(typedConnection)
 * ```
 */
inline fun <reified A : Action> actionCodecOf(
    json: Json = SerializableActionCodec.DefaultJson,
): ActionCodec<A> = SerializableActionCodec(serializer(), json)
