package io.flowdux.remote.serialization

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonMessageCodecTest {
    private val codec = JsonMessageCodec()

    @Test
    fun encodeActionMessageWrapsAsNestedObject() {
        val actionJson = """{"type":"Add","value":5}"""
        val result = codec.encodeActionMessage(actionJson)
        assertEquals(
            """{"type":"action","payload":{"type":"Add","value":5}}""",
            result,
        )
    }

    @Test
    fun decodeActionFromClientExtractsPayload() {
        val raw = """{"type":"action","payload":{"type":"Add","value":5}}"""
        val result = codec.decodeActionFromClient(raw)
        assertEquals("""{"type":"Add","value":5}""", result)
    }

    @Test
    fun encodeServerResponseCreatesObjectArray() {
        val actions = listOf("""{"type":"Add","value":10}""")
        val result = codec.encodeServerResponse(actions)
        assertEquals(
            """{"type":"response","actions":[{"type":"Add","value":10}]}""",
            result,
        )
    }

    @Test
    fun decodeServerMessageParsesActions() {
        val raw = """{"type":"response","actions":[{"type":"Add","value":10}]}"""
        val response = codec.decodeServerMessage(raw)

        assertEquals(1, response.actions.size)
        assertEquals("""{"type":"Add","value":10}""", response.actions[0])
    }

    @Test
    fun decodeServerMessageHandlesEmptyArray() {
        val raw = """{"type":"response","actions":[]}"""
        val response = codec.decodeServerMessage(raw)

        assertEquals(emptyList<String>(), response.actions)
    }

    @Test
    fun roundtripActionMessage() {
        val original = """{"type":"ServerAdd","value":42}"""

        val encoded = codec.encodeActionMessage(original)
        val decoded = codec.decodeActionFromClient(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun roundtripServerResponse() {
        val actions =
            listOf(
                """{"type":"Add","value":1}""",
                """{"type":"SetMessage","message":"hello"}""",
            )

        val encoded = codec.encodeServerResponse(actions)
        val decoded = codec.decodeServerMessage(encoded)

        assertEquals(actions, decoded.actions)
    }

    @Test
    fun handlesSpecialCharactersInValues() {
        val actionJson = """{"type":"SetMessage","message":"hello \"world\""}"""
        val encoded = codec.encodeActionMessage(actionJson)
        val decoded = codec.decodeActionFromClient(encoded)
        assertEquals(actionJson, decoded)
    }

    @Test
    fun roundtripNestedObjects() {
        val actionJson = """{"type":"Complex","data":{"nested":true,"list":[1,2,3]}}"""
        val encoded = codec.encodeActionMessage(actionJson)
        val decoded = codec.decodeActionFromClient(encoded)
        assertEquals(actionJson, decoded)
    }
}
