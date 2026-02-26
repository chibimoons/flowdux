package io.flowdux.remote.serialization

import io.flowdux.remote.MessageCodec
import io.flowdux.remote.ServerResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * [MessageCodec] implementation backed by `kotlinx.serialization`.
 *
 * Wire format:
 * - Client → Server: `{"type":"action","payload":{...}}`
 * - Server → Client: `{"type":"response","actions":[{...},...]}`
 */
class JsonMessageCodec(
    private val json: Json = Json,
) : MessageCodec {

    override fun encodeActionMessage(actionJson: String): String {
        return json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("type", "action")
            put("payload", JsonUnquotedLiteral(actionJson))
        })
    }

    override fun decodeActionFromClient(raw: String): String {
        val obj = json.parseToJsonElement(raw).jsonObject
        val payload = obj["payload"]
            ?: error("Missing \"payload\" field in client message: $raw")
        return payload.toString()
    }

    override fun encodeServerResponse(actions: List<String>): String {
        val elements = actions.map { JsonUnquotedLiteral(it) }
        return json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("type", "response")
            put("actions", JsonArray(elements))
        })
    }

    override fun decodeServerMessage(raw: String): ServerResponse {
        val obj = json.parseToJsonElement(raw).jsonObject
        val actionsElement = obj["actions"]
            ?: error("Missing \"actions\" field in server message: $raw")
        val actions = actionsElement.jsonArray.map { it.toString() }
        return ServerResponse(actions = actions)
    }
}
