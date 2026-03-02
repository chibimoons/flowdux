package io.flowdux.remote.multiplexer

import io.flowdux.Action
import io.flowdux.remote.serialization.actionCodecOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoutedActionSerializationTest {
    @Serializable
    sealed interface TestAction : Action {
        @Serializable data object Increment : TestAction

        @Serializable data class Add(val value: Int) : TestAction

        @Serializable data class SetName(val name: String) : TestAction
    }

    private val routedCodec = actionCodecOf<RoutedAction<TestAction>>()

    private fun routed(roomId: String, action: TestAction): RoutedAction<TestAction> = RoutedAction(roomId, action)

    @Test
    fun encodeDecodeRoutedAction() {
        val original = routed("room-1", TestAction.Add(42))
        val json = routedCodec.encode(original)
        val decoded = routedCodec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeDecodeDataObject() {
        val original = routed("room-2", TestAction.Increment)
        val json = routedCodec.encode(original)
        val decoded = routedCodec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeProducesExpectedFormat() {
        val action = routed("my-room", TestAction.Add(10))
        val json = routedCodec.encode(action)
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
                val original = routed(roomId, action)
                val encoded = routedCodec.encode(original)
                val decoded = routedCodec.decode(encoded)
                assertEquals(original, decoded, "Round-trip failed for $original")
            }
        }
    }

    @Test
    fun specialCharactersInRoomId() {
        val original = routed("room/with:special\"chars", TestAction.Increment)
        val json = routedCodec.encode(original)
        val decoded = routedCodec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun specialCharactersInActionContent() {
        val original = routed("room-1", TestAction.SetName("line1\nline2\ttab \"quoted\""))
        val json = routedCodec.encode(original)
        val decoded = routedCodec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun decodeMalformedJsonThrows() {
        assertFailsWith<SerializationException> {
            routedCodec.decode("not json at all")
        }
    }

    @Test
    fun decodeMissingRoomIdThrows() {
        assertFailsWith<SerializationException> {
            routedCodec.decode("""{"action":{"type":"Increment"}}""")
        }
    }

    @Test
    fun decodeMissingActionThrows() {
        assertFailsWith<SerializationException> {
            routedCodec.decode("""{"roomId":"room-1"}""")
        }
    }

    @Test
    fun decodeInvalidActionThrows() {
        assertFailsWith<SerializationException> {
            routedCodec.decode("""{"roomId":"room-1","action":{"type":"Unknown"}}""")
        }
    }
}
