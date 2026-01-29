package io.flowdux.remote.serialization

import io.flowdux.Action
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SerializableActionCodecTest {

    @Serializable
    sealed interface TestAction : Action {
        @Serializable data object Increment : TestAction
        @Serializable data class Add(val value: Int) : TestAction
        @Serializable data class SetName(val name: String) : TestAction
    }

    private val codec = SerializableActionCodec(TestAction.serializer())

    @Test
    fun encodeDecodeDataObject() {
        val json = codec.encode(TestAction.Increment)
        val decoded = codec.decode(json)
        assertEquals(TestAction.Increment, decoded)
    }

    @Test
    fun encodeDecodeDataClass() {
        val original = TestAction.Add(42)
        val json = codec.encode(original)
        val decoded = codec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeDecodeStringField() {
        val original = TestAction.SetName("hello world")
        val json = codec.encode(original)
        val decoded = codec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeDecodeSpecialCharacters() {
        val original = TestAction.SetName("line1\nline2\ttab \"quoted\"")
        val json = codec.encode(original)
        val decoded = codec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripAllVariants() {
        val actions = listOf(
            TestAction.Increment,
            TestAction.Add(0),
            TestAction.Add(-1),
            TestAction.SetName(""),
            TestAction.SetName("test"),
        )
        for (action in actions) {
            val encoded = codec.encode(action)
            val decoded = codec.decode(encoded)
            assertEquals(action, decoded, "Round-trip failed for $action")
        }
    }

    @Test
    fun classDiscriminatorUsesTypeField() {
        val json = codec.encode(TestAction.Add(10))
        assert(json.contains("\"type\":")) { "Expected 'type' discriminator in: $json" }
    }

    @Test
    fun decodeMalformedJsonThrows() {
        assertFailsWith<SerializationException> {
            codec.decode("not json at all")
        }
    }

    @Test
    fun decodeUnknownTypeThrows() {
        assertFailsWith<SerializationException> {
            codec.decode("""{"type":"NonExistent","value":1}""")
        }
    }

    @Test
    fun decodeMissingRequiredFieldThrows() {
        assertFailsWith<SerializationException> {
            codec.decode("""{"type":"io.flowdux.remote.serialization.SerializableActionCodecTest.TestAction.Add"}""")
        }
    }
}
