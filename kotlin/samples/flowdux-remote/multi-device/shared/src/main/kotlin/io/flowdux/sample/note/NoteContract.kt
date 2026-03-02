package io.flowdux.sample.note

import io.flowdux.Action
import io.flowdux.State
import io.flowdux.remote.ClientSharedAction
import io.flowdux.remote.ServerSharedAction
import kotlinx.serialization.Serializable

interface NoteAction : Action

/** Actions that cross the wire between client and server. */
@Serializable
sealed interface SharedNoteAction : NoteAction {
    // Client → Server
    @Serializable data class AddNote(val title: String, val content: String) :
        SharedNoteAction,
        ServerSharedAction

    @Serializable data class DeleteNote(val noteId: String) :
        SharedNoteAction,
        ServerSharedAction

    @Serializable data class EditNote(val noteId: String, val title: String, val content: String) :
        SharedNoteAction,
        ServerSharedAction

    @Serializable data class DeviceConnected(val deviceName: String) :
        SharedNoteAction,
        ServerSharedAction

    @Serializable data class DeviceDisconnected(val deviceName: String) :
        SharedNoteAction,
        ServerSharedAction

    // Server → Client
    @Serializable data class SyncState(val state: NoteState) :
        SharedNoteAction,
        ClientSharedAction
}

// -- State --
@Serializable
data class NoteState(
    val notes: List<Note> = emptyList(),
    val connectedDevices: Set<String> = emptySet(),
    val lastEvent: NoteEvent? = null,
) : State

@Serializable
data class Note(val id: String, val title: String, val content: String)

@Serializable
sealed interface NoteEvent {
    @Serializable data class NoteAdded(val note: Note) : NoteEvent

    @Serializable data class NoteDeleted(val noteId: String) : NoteEvent

    @Serializable data class NoteEdited(val note: Note) : NoteEvent

    @Serializable data class DeviceJoined(val deviceName: String) : NoteEvent

    @Serializable data class DeviceLeft(val deviceName: String) : NoteEvent
}
