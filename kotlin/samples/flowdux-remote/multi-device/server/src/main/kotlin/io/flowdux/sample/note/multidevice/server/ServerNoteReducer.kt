package io.flowdux.sample.note.multidevice.server

import io.flowdux.Reducer
import io.flowdux.buildReducer
import io.flowdux.sample.note.Note
import io.flowdux.sample.note.NoteAction
import io.flowdux.sample.note.NoteEvent

val serverNoteReducer: Reducer<ServerNoteState, NoteAction> = buildReducer {
    on<ServerNoteAction.NoteAdded> { state, action ->
        val note = Note(id = action.id, title = action.title, content = action.content)
        state.copy(
            notes = state.notes + note,
            lastEvent = NoteEvent.NoteAdded(note),
            totalEdits = state.totalEdits + 1,
        )
    }
    on<ServerNoteAction.NoteDeleted> { state, action ->
        state.copy(
            notes = state.notes.filter { it.id != action.noteId },
            lastEvent = NoteEvent.NoteDeleted(action.noteId),
            totalEdits = state.totalEdits + 1,
        )
    }
    on<ServerNoteAction.NoteEdited> { state, action ->
        val updatedNote = Note(id = action.noteId, title = action.title, content = action.content)
        state.copy(
            notes = state.notes.map { if (it.id == action.noteId) updatedNote else it },
            lastEvent = NoteEvent.NoteEdited(updatedNote),
            totalEdits = state.totalEdits + 1,
        )
    }
    on<ServerNoteAction.DeviceJoined> { state, action ->
        state.copy(
            connectedDevices = state.connectedDevices + action.deviceName,
            lastEvent = NoteEvent.DeviceJoined(action.deviceName),
        )
    }
    on<ServerNoteAction.DeviceLeft> { state, action ->
        state.copy(
            connectedDevices = state.connectedDevices - action.deviceName,
            lastEvent = NoteEvent.DeviceLeft(action.deviceName),
        )
    }
}
