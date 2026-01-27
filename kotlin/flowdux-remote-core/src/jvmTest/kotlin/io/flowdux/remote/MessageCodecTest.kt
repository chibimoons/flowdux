package io.flowdux.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MessageCodecTest {

    private val codec = JsonMessageCodec()

    @Test
    fun `encodeActionMessage wraps action JSON in envelope`() {
        val actionJson = """{"type":"Add","value":5}"""
        val result = codec.encodeActionMessage(actionJson)
        assertEquals(
            """{"type":"action","payload":"{\"type\":\"Add\",\"value\":5}"}""",
            result,
        )
    }

    @Test
    fun `decodeActionFromClient extracts payload`() {
        val raw = """{"type":"action","payload":"{\"type\":\"Add\",\"value\":5}"}"""
        val result = codec.decodeActionFromClient(raw)
        assertEquals("""{"type":"Add","value":5}""", result)
    }

    @Test
    fun `encodeServerResponse creates response envelope`() {
        val actions = listOf("""{"type":"Add","value":10}""")

        val result = codec.encodeServerResponse(actions)

        assertEquals(
            """{"type":"response","actions":["{\"type\":\"Add\",\"value\":10}"]}""",
            result,
        )
    }

    @Test
    fun `decodeServerMessage parses actions`() {
        val raw = """{"type":"response","actions":["{\"type\":\"Add\",\"value\":10}"]}"""
        val response = codec.decodeServerMessage(raw)

        assertEquals(1, response.actions.size)
        assertEquals("""{"type":"Add","value":10}""", response.actions[0])
    }

    @Test
    fun `decodeServerMessage handles empty array`() {
        val raw = """{"type":"response","actions":[]}"""
        val response = codec.decodeServerMessage(raw)

        assertEquals(emptyList<String>(), response.actions)
    }

    @Test
    fun `roundtrip action message encoding`() {
        val original = """{"type":"ServerAdd","value":42}"""

        val encoded = codec.encodeActionMessage(original)
        val decoded = codec.decodeActionFromClient(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `roundtrip server response encoding`() {
        val actions = listOf(
            """{"type":"Add","value":1}""",
            """{"type":"SetMessage","message":"hello"}""",
        )

        val encoded = codec.encodeServerResponse(actions)
        val decoded = codec.decodeServerMessage(encoded)

        assertEquals(actions, decoded.actions)
    }

    @Test
    fun `handles special characters in JSON values`() {
        val actionJson = """{"type":"SetMessage","message":"hello \"world\""}"""
        val encoded = codec.encodeActionMessage(actionJson)
        val decoded = codec.decodeActionFromClient(encoded)
        assertEquals(actionJson, decoded)
    }
}
