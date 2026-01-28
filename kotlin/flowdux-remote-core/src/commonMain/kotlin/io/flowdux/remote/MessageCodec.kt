package io.flowdux.remote

/**
 * Codec for encoding/decoding wire-level message envelopes.
 *
 * Handles the framing protocol between client and server:
 * - Client → Server: `{"type":"action","payload":"<actionJson>"}`
 * - Server → Client: `{"type":"response","actions":[...]}`
 */
interface MessageCodec {
    /** Wrap an action JSON string into a client→server message envelope. */
    fun encodeActionMessage(actionJson: String): String

    /** Parse a raw server→client message into a [ServerResponse]. */
    fun decodeServerMessage(raw: String): ServerResponse

    /** Extract the action JSON payload from a client→server message envelope. */
    fun decodeActionFromClient(raw: String): String

    /** Encode a server response (action JSONs) into a wire message. */
    fun encodeServerResponse(actions: List<String>): String
}

/**
 * Simple JSON-based [MessageCodec] implementation using manual string construction.
 *
 * Wire format:
 * - Client → Server: `{"type":"action","payload":"<escaped-json>"}`
 * - Server → Client: `{"type":"response","actions":["<escaped>",...]}`
 */
class JsonMessageCodec : MessageCodec {

    override fun encodeActionMessage(actionJson: String): String {
        val escaped = escapeJson(actionJson)
        return """{"type":"action","payload":"$escaped"}"""
    }

    override fun decodeServerMessage(raw: String): ServerResponse {
        val actions = extractJsonArray(raw, "actions")
        return ServerResponse(actions = actions)
    }

    override fun decodeActionFromClient(raw: String): String {
        return extractJsonString(raw, "payload")
    }

    override fun encodeServerResponse(actions: List<String>): String {
        val actionsArray = actions.joinToString(",") { "\"${escapeJson(it)}\"" }
        return """{"type":"response","actions":[$actionsArray]}"""
    }

    internal companion object {
        fun escapeJson(value: String): String {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }

        fun unescapeJson(value: String): String {
            val sb = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                if (value[i] == '\\' && i + 1 < value.length) {
                    when (value[i + 1]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        else -> { sb.append(value[i]); i++; continue }
                    }
                    i += 2
                } else {
                    sb.append(value[i])
                    i++
                }
            }
            return sb.toString()
        }

        fun extractJsonString(json: String, key: String): String {
            val keyPattern = "\"$key\":\""
            val startIdx = json.indexOf(keyPattern)
            if (startIdx == -1) return ""
            val valueStart = startIdx + keyPattern.length
            val valueEnd = findClosingQuote(json, valueStart)
            return unescapeJson(json.substring(valueStart, valueEnd))
        }

        fun extractJsonArray(json: String, key: String): List<String> {
            val keyPattern = "\"$key\":["
            val startIdx = json.indexOf(keyPattern)
            if (startIdx == -1) return emptyList()
            val arrayStart = startIdx + keyPattern.length
            val arrayEnd = json.indexOf(']', arrayStart)
            if (arrayEnd == -1 || arrayStart == arrayEnd) return emptyList()

            val arrayContent = json.substring(arrayStart, arrayEnd)
            return splitJsonStringArray(arrayContent)
        }

        private fun findClosingQuote(json: String, start: Int): Int {
            var i = start
            while (i < json.length) {
                if (json[i] == '\\') {
                    i += 2
                    continue
                }
                if (json[i] == '"') return i
                i++
            }
            return json.length
        }

        private fun splitJsonStringArray(content: String): List<String> {
            val results = mutableListOf<String>()
            var i = 0
            while (i < content.length) {
                if (content[i] == '"') {
                    val valueStart = i + 1
                    val valueEnd = findClosingQuote(content, valueStart)
                    results.add(unescapeJson(content.substring(valueStart, valueEnd)))
                    i = valueEnd + 1
                } else {
                    i++
                }
            }
            return results
        }
    }
}
