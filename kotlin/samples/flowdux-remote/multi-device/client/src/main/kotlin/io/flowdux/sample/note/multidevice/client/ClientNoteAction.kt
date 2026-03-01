package io.flowdux.sample.note.multidevice.client

import io.flowdux.sample.note.NoteAction

sealed interface ClientNoteAction : NoteAction {
    data object Connect : ClientNoteAction

    data object Disconnect : ClientNoteAction

    data object Connected : ClientNoteAction

    data object Disconnected : ClientNoteAction
}
