package io.flowdux.sample.chat

import io.flowdux.remote.ActionCodec

class ChatActionCodec : ActionCodec<ChatAction> {

    override fun encode(action: ChatAction): String = when (action) {
        is ChatAction.Connect -> """{"type":"Connect"}"""
        is ChatAction.Disconnect -> """{"type":"Disconnect"}"""
        is ChatAction.StartListening -> """{"type":"StartListening"}"""
        is ChatAction.SendMessage ->
            """{"type":"SendMessage","user":"${escape(action.user)}","text":"${escape(action.text)}"}"""
        is ChatAction.JoinRoom ->
            """{"type":"JoinRoom","user":"${escape(action.user)}"}"""
        is ChatAction.LeaveRoom ->
            """{"type":"LeaveRoom","user":"${escape(action.user)}"}"""
        is ChatAction.MessageReceived ->
            """{"type":"MessageReceived","user":"${escape(action.user)}","text":"${escape(action.text)}"}"""
        is ChatAction.UserJoined ->
            """{"type":"UserJoined","user":"${escape(action.user)}"}"""
        is ChatAction.UserLeft ->
            """{"type":"UserLeft","user":"${escape(action.user)}"}"""
    }

    override fun decode(json: String): ChatAction {
        val type = extractString(json, "type")
        return when (type) {
            "Connect" -> ChatAction.Connect
            "Disconnect" -> ChatAction.Disconnect
            "StartListening" -> ChatAction.StartListening
            "SendMessage" -> ChatAction.SendMessage(
                user = extractString(json, "user"),
                text = extractString(json, "text"),
            )
            "JoinRoom" -> ChatAction.JoinRoom(user = extractString(json, "user"))
            "LeaveRoom" -> ChatAction.LeaveRoom(user = extractString(json, "user"))
            "MessageReceived" -> ChatAction.MessageReceived(
                user = extractString(json, "user"),
                text = extractString(json, "text"),
            )
            "UserJoined" -> ChatAction.UserJoined(user = extractString(json, "user"))
            "UserLeft" -> ChatAction.UserLeft(user = extractString(json, "user"))
            else -> error("Unknown ChatAction type: $type")
        }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun unescape(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")

    private fun extractString(json: String, key: String): String {
        val keyPattern = "\"$key\":\""
        val startIdx = json.indexOf(keyPattern)
        if (startIdx == -1) return ""
        val valueStart = startIdx + keyPattern.length
        val valueEnd = findClosingQuote(json, valueStart)
        return unescape(json.substring(valueStart, valueEnd))
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
}
