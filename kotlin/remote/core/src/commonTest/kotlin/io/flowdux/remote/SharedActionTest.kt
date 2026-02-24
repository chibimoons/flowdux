package io.flowdux.remote

import io.flowdux.Action
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedActionTest {

    // -- Test domain actions --

    interface ChatAction : Action

    @Serializable
    sealed interface SharedChatAction : ChatAction {
        @Serializable
        data class SendMessage(val text: String) : SharedChatAction, ServerSharedAction

        @Serializable
        data class JoinRoom(val user: String) : SharedChatAction, ServerSharedAction

        @Serializable
        data class SyncState(val messages: List<String>) : SharedChatAction, ClientSharedAction

        @Serializable
        data class Kicked(val reason: String) : SharedChatAction, ClientSharedAction
    }

    data class LocalAction(val value: Int) : ChatAction

    // -- Type hierarchy tests --

    @Test
    fun serverSharedActionExtendsSharedAction() {
        val action: Action = SharedChatAction.SendMessage("hello")
        assertTrue(action is SharedAction)
        assertTrue(action is ServerSharedAction)
        assertFalse(action is ClientSharedAction)
    }

    @Test
    fun clientSharedActionExtendsSharedAction() {
        val action: Action = SharedChatAction.SyncState(listOf("msg"))
        assertTrue(action is SharedAction)
        assertTrue(action is ClientSharedAction)
        assertFalse(action is ServerSharedAction)
    }

    @Test
    fun localActionIsNotSharedAction() {
        val action: Action = LocalAction(42)
        assertFalse(action is SharedAction)
        assertFalse(action is ServerSharedAction)
        assertFalse(action is ClientSharedAction)
    }

    // -- Filtering tests --

    @Test
    fun filterServerSharedActionsFromMixedList() {
        val actions: List<Action> = listOf(
            SharedChatAction.SendMessage("hello"),
            LocalAction(1),
            SharedChatAction.SyncState(listOf("msg")),
            SharedChatAction.JoinRoom("alice"),
        )

        val serverActions = actions.filterIsInstance<ServerSharedAction>()
        assertEquals(2, serverActions.size)
        assertIs<SharedChatAction.SendMessage>(serverActions[0])
        assertIs<SharedChatAction.JoinRoom>(serverActions[1])
    }

    @Test
    fun filterClientSharedActionsFromMixedList() {
        val actions: List<Action> = listOf(
            SharedChatAction.SendMessage("hello"),
            SharedChatAction.SyncState(listOf("msg")),
            LocalAction(1),
            SharedChatAction.Kicked("spam"),
        )

        val clientActions = actions.filterIsInstance<ClientSharedAction>()
        assertEquals(2, clientActions.size)
        assertIs<SharedChatAction.SyncState>(clientActions[0])
        assertIs<SharedChatAction.Kicked>(clientActions[1])
    }

    @Test
    fun filterSharedActionsExcludesLocalActions() {
        val actions: List<Action> = listOf(
            SharedChatAction.SendMessage("hello"),
            LocalAction(1),
            LocalAction(2),
            SharedChatAction.SyncState(listOf("msg")),
        )

        val sharedActions = actions.filterIsInstance<SharedAction>()
        assertEquals(2, sharedActions.size)
    }

    // -- Serialization tests --

    private val json = Json

    @Test
    fun serializeAndDeserializeServerSharedAction() {
        val original = SharedChatAction.SendMessage("hello")
        val encoded = json.encodeToString<SharedChatAction>(original)
        val decoded = json.decodeFromString<SharedChatAction>(encoded)

        assertEquals(original, decoded)
        assertTrue(decoded is ServerSharedAction)
        assertFalse(decoded is ClientSharedAction)
    }

    @Test
    fun serializeAndDeserializeClientSharedAction() {
        val original = SharedChatAction.SyncState(listOf("msg1", "msg2"))
        val encoded = json.encodeToString<SharedChatAction>(original)
        val decoded = json.decodeFromString<SharedChatAction>(encoded)

        assertEquals(original, decoded)
        assertTrue(decoded is ClientSharedAction)
        assertFalse(decoded is ServerSharedAction)
    }

    @Test
    fun polymorphicSerializationPreservesDirectionMarkers() {
        val actions = listOf<SharedChatAction>(
            SharedChatAction.SendMessage("hello"),
            SharedChatAction.JoinRoom("alice"),
            SharedChatAction.SyncState(listOf("msg")),
            SharedChatAction.Kicked("spam"),
        )

        for (action in actions) {
            val encoded = json.encodeToString<SharedChatAction>(action)
            val decoded = json.decodeFromString<SharedChatAction>(encoded)

            assertEquals(action, decoded)
            assertEquals(action is ServerSharedAction, decoded is ServerSharedAction)
            assertEquals(action is ClientSharedAction, decoded is ClientSharedAction)
        }
    }

    @Test
    fun serializedJsonContainsTypeDiscriminator() {
        val action = SharedChatAction.SendMessage("hello")
        val encoded = json.encodeToString<SharedChatAction>(action)

        // Polymorphic serialization includes type discriminator
        assertTrue(encoded.contains("type"), "JSON should contain type discriminator: $encoded")
        assertTrue(encoded.contains("SendMessage"), "JSON should contain class name: $encoded")
    }

    @Test
    fun emptyCollectionFieldSerializesCorrectly() {
        val original = SharedChatAction.SyncState(emptyList())
        val encoded = json.encodeToString<SharedChatAction>(original)
        val decoded = json.decodeFromString<SharedChatAction>(encoded)

        assertEquals(original, decoded)
        assertIs<SharedChatAction.SyncState>(decoded)
        assertEquals(emptyList(), (decoded as SharedChatAction.SyncState).messages)
    }
}
