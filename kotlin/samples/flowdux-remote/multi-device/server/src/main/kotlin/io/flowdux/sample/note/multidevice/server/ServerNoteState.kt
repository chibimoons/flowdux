package io.flowdux.sample.note.multidevice.server

import io.flowdux.State
import io.flowdux.sample.note.Note
import io.flowdux.sample.note.NoteEvent

data class ServerNoteState(
    val userId: String = "",
    val notes: List<Note> = emptyList(),
    val connectedDevices: Set<String> = emptySet(),
    val lastEvent: NoteEvent? = null,
    val totalEdits: Int = 0,
) : State
