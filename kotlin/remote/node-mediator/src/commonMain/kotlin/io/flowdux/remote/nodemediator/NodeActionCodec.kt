package io.flowdux.remote.nodemediator

import io.flowdux.Action
import io.flowdux.remote.ActionCodec
import io.flowdux.remote.serialization.SerializableActionCodec
import io.flowdux.remote.serialization.actionCodecOf
import kotlinx.serialization.json.Json

/**
 * [ActionCodec] implementation for [NodeAction] that wraps an inner [ActionCodec].
 *
 * This codec serializes [NodeAction] instances to JSON with the format:
 * ```json
 * {"roomId": "room-1", "action": {"type": "SendMessage", "text": "hello"}}
 * ```
 *
 * Usage:
 * ```kotlin
 * val innerCodec = actionCodecOf<ChatAction>()
 * val nodeCodec = NodeActionCodec(innerCodec)
 * // or use the extension function:
 * val nodeCodec = innerCodec.nodeRouted()
 * ```
 */
class NodeActionCodec<A : Action>(
    private val actionCodec: ActionCodec<A>,
    private val json: Json = SerializableActionCodec.DefaultJson,
) : ActionCodec<NodeAction<A>> {

    override fun encode(action: NodeAction<A>): String {
        val actionJson = actionCodec.encode(action.action)
        return """{"roomId":"${escapeJsonString(action.roomId)}","action":$actionJson}"""
    }

    override fun decode(json: String): NodeAction<A> {
        val parsed = this.json.parseToJsonElement(json)
        val obj = parsed as? kotlinx.serialization.json.JsonObject
            ?: throw kotlinx.serialization.SerializationException("Expected JSON object")

        val roomId = (obj["roomId"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: throw kotlinx.serialization.SerializationException("Missing 'roomId' field")

        val actionElement = obj["action"]
            ?: throw kotlinx.serialization.SerializationException("Missing 'action' field")

        val actionJsonString = actionElement.toString()
        val action = actionCodec.decode(actionJsonString)

        return NodeAction(roomId, action)
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
 * Wraps this [ActionCodec] to create a [NodeActionCodec] for node-mediated connections.
 *
 * Usage:
 * ```kotlin
 * val innerCodec = actionCodecOf<ChatAction>()
 * val nodeCodec = innerCodec.nodeRouted()
 * ```
 */
fun <A : Action> ActionCodec<A>.nodeRouted(
    json: Json = SerializableActionCodec.DefaultJson,
): ActionCodec<NodeAction<A>> = NodeActionCodec(this, json)

/**
 * Creates a [NodeActionCodec] for the reified action type [A] using `kotlinx.serialization`.
 *
 * Usage:
 * ```kotlin
 * val nodeCodec = nodeRoutedActionCodecOf<ChatAction>()
 * ```
 */
inline fun <reified A : Action> nodeRoutedActionCodecOf(
    json: Json = SerializableActionCodec.DefaultJson,
): ActionCodec<NodeAction<A>> = actionCodecOf<A>(json).nodeRouted(json)
