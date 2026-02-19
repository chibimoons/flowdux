package io.flowdux.sample.note.multidevice.client

import io.flowdux.ActionProcessorMap
import io.flowdux.remote.SyncMiddleware
import io.flowdux.remote.TypedClientConnection
import io.flowdux.sample.note.NoteAction

class NoteRemoteMiddleware(
    connection: TypedClientConnection<NoteAction>,
) : SyncMiddleware<ClientNoteState, NoteAction>(
    connection = connection,
) {
    override val processors: ActionProcessorMap<ClientNoteState, NoteAction> = buildProcessors {
        on<ClientNoteAction.Connect> { _, _ ->
            startConnection()
            emit(ClientNoteAction.Connected)
        }
        on<ClientNoteAction.Disconnect> { _, _ ->
            stopConnection()
            emit(ClientNoteAction.Disconnected)
        }
    }
}
