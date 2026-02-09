package io.flowdux.remote.multiplexer

import io.flowdux.Action
import io.flowdux.remote.ActionCodec
import io.flowdux.remote.serialization.SerializableActionCodec
import io.flowdux.remote.serialization.actionCodecOf
import kotlinx.serialization.json.Json

/**
 * [ActionCodec] implementation for [RoutedAction] that wraps an inner [ActionCodec].
 *
 * This codec serializes [RoutedAction] instances to JSON with the format:
 * ```json
 * {"roomId": "room-1", "action": {"type": "SendMessage", "text": "hello"}}
 * ```
 *
 * Usage:
 * ```kotlin
 * val innerCodec = actionCodecOf<ChatAction>()
 * val routedCodec = RoutedActionCodec(innerCodec)
 * // or use the extension function:
 * val routedCodec = innerCodec.routed()
 * ```
 */
class RoutedActionCodec<A : Action>(
    private val actionCodec: ActionCodec<A>,
    private val json: Json = SerializableActionCodec.DefaultJson,
) : ActionCodec<RoutedAction<A>> {

    override fun encode(action: RoutedAction<A>): String {
        val actionJson = actionCodec.encode(action.action)
        return """{"roomId":"${escapeJsonString(action.roomId)}","action":$actionJson}"""
    }

    override fun decode(json: String): RoutedAction<A> {
        val parsed = this.json.parseToJsonElement(json)
        val obj = parsed as? kotlinx.serialization.json.JsonObject
            ?: throw kotlinx.serialization.SerializationException("Expected JSON object")

        val roomId = (obj["roomId"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: throw kotlinx.serialization.SerializationException("Missing 'roomId' field")

        val actionElement = obj["action"]
            ?: throw kotlinx.serialization.SerializationException("Missing 'action' field")

        val actionJsonString = actionElement.toString()
        val action = actionCodec.decode(actionJsonString)

        return RoutedAction(roomId, action)
    }

    private fun escapeJsonString(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u${c.code.toString(16).padStart(4, '0')}") else append(c)
            }
        }
    }
}

/**
 * Wraps this [ActionCodec] to create a [RoutedActionCodec] for multiplexed connections.
 *
 * Usage:
 * ```kotlin
 * val innerCodec = actionCodecOf<ChatAction>()
 * val routedCodec = innerCodec.routed()
 * ```
 */
fun <A : Action> ActionCodec<A>.routed(
    json: Json = SerializableActionCodec.DefaultJson,
): ActionCodec<RoutedAction<A>> = RoutedActionCodec(this, json)

/**
 * Creates a [RoutedActionCodec] for the reified action type [A] using `kotlinx.serialization`.
 *
 * Usage:
 * ```kotlin
 * val routedCodec = routedActionCodecOf<ChatAction>()
 * ```
 */
inline fun <reified A : Action> routedActionCodecOf(
    json: Json = SerializableActionCodec.DefaultJson,
): ActionCodec<RoutedAction<A>> = actionCodecOf<A>(json).routed(json)
