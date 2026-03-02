package io.flowdux.remote.nodemediator

import io.flowdux.Action
import io.flowdux.remote.serialization.actionCodecOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NodeActionCodecTest {
    @Serializable
    sealed interface TestAction : Action {
        @Serializable data object Increment : TestAction

        @Serializable data class Add(val value: Int) : TestAction

        @Serializable data class SetName(val name: String) : TestAction
    }

    private val codec = actionCodecOf<NodeAction<TestAction>>()

    private fun nodeAction(roomId: String, action: TestAction): NodeAction<TestAction> = NodeAction(roomId, action)

    @Test
    fun encodeDecodeNodeAction() {
        val original = nodeAction("room-1", TestAction.Add(42))
        val json = codec.encode(original)
        val decoded = codec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeDecodeDataObject() {
        val original = nodeAction("room-2", TestAction.Increment)
        val json = codec.encode(original)
        val decoded = codec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeProducesExpectedFormat() {
        val action = nodeAction("my-room", TestAction.Add(10))
        val json = codec.encode(action)
        assertTrue(json.contains("\"roomId\":\"my-room\""), "Expected roomId field in: $json")
        assertTrue(json.contains("\"action\":"), "Expected action field in: $json")
    }

    @Test
    fun roundTripAllVariants() {
        val rooms = listOf("room-1", "room-2", "test-room", "")
        val actions: List<TestAction> =
            listOf(
                TestAction.Increment,
                TestAction.Add(0),
                TestAction.Add(-1),
                TestAction.Add(Int.MAX_VALUE),
                TestAction.SetName(""),
                TestAction.SetName("hello"),
            )
        for (roomId in rooms) {
            for (action in actions) {
                val original = nodeAction(roomId, action)
                val encoded = codec.encode(original)
                val decoded = codec.decode(encoded)
                assertEquals(original, decoded, "Round-trip failed for $original")
            }
        }
    }

    @Test
    fun specialCharactersInRoomId() {
        val original = nodeAction("room/with:special\"chars", TestAction.Increment)
        val json = codec.encode(original)
        val decoded = codec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun specialCharactersInActionContent() {
        val original = nodeAction("room-1", TestAction.SetName("line1\nline2\ttab \"quoted\""))
        val json = codec.encode(original)
        val decoded = codec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun decodeMalformedJsonThrows() {
        assertFailsWith<SerializationException> {
            codec.decode("not json at all")
        }
    }

    @Test
    fun decodeMissingRoomIdThrows() {
        assertFailsWith<SerializationException> {
            codec.decode("""{"action":{"type":"Increment"}}""")
        }
    }

    @Test
    fun decodeMissingActionThrows() {
        assertFailsWith<SerializationException> {
            codec.decode("""{"roomId":"room-1"}""")
        }
    }

    @Test
    fun decodeInvalidActionThrows() {
        assertFailsWith<SerializationException> {
            codec.decode("""{"roomId":"room-1","action":{"type":"Unknown"}}""")
        }
    }
}
