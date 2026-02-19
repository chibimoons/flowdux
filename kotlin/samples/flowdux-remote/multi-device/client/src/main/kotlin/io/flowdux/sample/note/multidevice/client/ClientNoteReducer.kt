package io.flowdux.sample.note.multidevice.client

import io.flowdux.Reducer
import io.flowdux.buildReducer
import io.flowdux.sample.note.NoteAction
import io.flowdux.sample.note.SharedNoteAction

val clientNoteReducer: Reducer<ClientNoteState, NoteAction> = buildReducer {
    on<SharedNoteAction.SyncState> { state, action ->
        state.copy(
            notes = action.state.notes,
            connectedDevices = action.state.connectedDevices,
            lastEvent = action.state.lastEvent,
        )
    }
    on<ClientNoteAction.Connected> { state, _ ->
        state.copy(isConnected = true)
    }
    on<ClientNoteAction.Disconnected> { state, _ ->
        state.copy(isConnected = false)
    }
}
