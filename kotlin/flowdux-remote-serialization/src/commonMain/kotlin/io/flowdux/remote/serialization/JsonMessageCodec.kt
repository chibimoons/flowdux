package io.flowdux.remote.serialization

import io.flowdux.remote.MessageCodec
import io.flowdux.remote.ServerResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
        val payload = json.parseToJsonElement(actionJson)
        return json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("type", "action")
            put("payload", payload)
        })
    }

    override fun decodeActionFromClient(raw: String): String {
        val obj = json.parseToJsonElement(raw).jsonObject
        return json.encodeToString(JsonElement.serializer(), obj["payload"]!!)
    }

    override fun encodeServerResponse(actions: List<String>): String {
        val elements = actions.map { json.parseToJsonElement(it) }
        return json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("type", "response")
            put("actions", JsonArray(elements))
        })
    }

    override fun decodeServerMessage(raw: String): ServerResponse {
        val obj = json.parseToJsonElement(raw).jsonObject
        val actions = obj["actions"]!!.jsonArray.map {
            json.encodeToString(JsonElement.serializer(), it)
        }
        return ServerResponse(actions = actions)
    }
}
