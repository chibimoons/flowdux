package io.flowdux.sample.note.multidevice.client

import io.flowdux.State
import io.flowdux.sample.note.Note
import io.flowdux.sample.note.NoteEvent

data class ClientNoteState(
    val userId: String = "",
    val deviceName: String = "",
    val notes: List<Note> = emptyList(),
    val connectedDevices: Set<String> = emptySet(),
    val lastEvent: NoteEvent? = null,
    val isConnected: Boolean = false,
) : State
