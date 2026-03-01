package io.flowdux.sample.note.multidevice.server

import io.flowdux.sample.note.NoteAction

sealed interface ServerNoteAction : NoteAction {
    data class NoteAdded(val id: String, val title: String, val content: String) : ServerNoteAction

    data class NoteDeleted(val noteId: String) : ServerNoteAction

    data class NoteEdited(val noteId: String, val title: String, val content: String) : ServerNoteAction

    data class DeviceJoined(val deviceName: String) : ServerNoteAction

    data class DeviceLeft(val deviceName: String) : ServerNoteAction
}
