package io.flowdux.sample.note.multidevice.client

import io.flowdux.Store
import io.flowdux.remote.TypedClientConnection
import io.flowdux.remote.createClientStore
import io.flowdux.remote.ktor.KtorWebSocketClientConnection
import io.flowdux.remote.serialization.typedJson
import io.flowdux.sample.note.NoteAction
import io.flowdux.sample.note.NoteEvent
import io.flowdux.sample.note.SharedNoteAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Multi-Device Notes Client Demo
 *
 * Each client instance represents a device (phone, tablet, desktop) for a specific user.
 * All devices of the same user share the same notes via the server room (roomId = userId).
 *
 * Usage: ./gradlew :kotlin:sample-remote-multidevice:client:run --args="<userId> <deviceName>"
 *
 * Commands:
 * - /add <title> | <content> — Add a new note
 * - /edit <noteId> <title> | <content> — Edit an existing note
 * - /delete <noteId> — Delete a note
 * - /list — List all notes
 * - /devices — Show connected devices
 * - /quit — Exit
 */
fun main(args: Array<String>) = runBlocking {
    val userId = args.getOrNull(0) ?: run {
        print("Enter user ID: ")
        System.out.flush()
        readlnOrNull()?.trim()?.ifEmpty { null }
    } ?: run {
        println("No user ID provided. Exiting.")
        return@runBlocking
    }

    val deviceName = args.getOrNull(1) ?: run {
        print("Enter device name (phone/tablet/desktop): ")
        System.out.flush()
        readlnOrNull()?.trim()?.ifEmpty { null }
    } ?: run {
        println("No device name provided. Exiting.")
        return@runBlocking
    }

    println("""

        ╔══════════════════════════════════════════════════════╗
        ║     FlowDux Multi-Device Notes Client                ║
        ╠══════════════════════════════════════════════════════╣
        ║  User: $userId
        ║  Device: $deviceName
        ╠══════════════════════════════════════════════════════╣
        ║  Commands:                                           ║
        ║    /add <title> | <content>       - Add a note      ║
        ║    /edit <id> <title> | <content> - Edit a note     ║
        ║    /delete <id>                   - Delete a note   ║
        ║    /list                          - List all notes  ║
        ║    /devices                       - Show devices    ║
        ║    /quit                          - Exit            ║
        ╚══════════════════════════════════════════════════════╝

    """.trimIndent())

    // Connect to the user's room (roomId = userId)
    val store = createNoteStore(userId)

    // Observe state changes
    val collectorJob = launch {
        store.state.collect { state ->
            when (val event = state.lastEvent) {
                is NoteEvent.NoteAdded ->
                    println("  + Note added: [${event.note.id}] ${event.note.title}")
                is NoteEvent.NoteDeleted ->
                    println("  - Note deleted: ${event.noteId}")
                is NoteEvent.NoteEdited ->
                    println("  ~ Note edited: [${event.note.id}] ${event.note.title}")
                is NoteEvent.DeviceJoined ->
                    println("  * Device joined: ${event.deviceName} (online: ${state.connectedDevices})")
                is NoteEvent.DeviceLeft ->
                    println("  * Device left: ${event.deviceName} (online: ${state.connectedDevices})")
                null -> {}
            }
        }
    }

    // Connect and announce device
    store.dispatch(ClientNoteAction.Connect)
    delay(500)
    store.dispatch(SharedNoteAction.DeviceConnected(deviceName))
    delay(200)

    println("  Connected as $userId/$deviceName\n")

    // Interactive input loop
    withContext(Dispatchers.IO) {
        while (true) {
            val line = readlnOrNull() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.equals("/quit", ignoreCase = true) -> break

                trimmed.equals("/list", ignoreCase = true) -> {
                    val notes = store.currentState.notes
                    if (notes.isEmpty()) {
                        println("  (no notes)")
                    } else {
                        println("  Notes (${notes.size}):")
                        notes.forEach { println("    [${it.id}] ${it.title}: ${it.content}") }
                    }
                }

                trimmed.equals("/devices", ignoreCase = true) -> {
                    val devices = store.currentState.connectedDevices
                    println("  Connected devices: $devices")
                }

                trimmed.startsWith("/add ", ignoreCase = true) -> {
                    val rest = trimmed.substring("/add ".length).trim()
                    val parts = rest.split("|", limit = 2).map { it.trim() }
                    val title = parts[0]
                    val content = parts.getOrElse(1) { "" }
                    if (title.isNotEmpty()) {
                        store.dispatch(SharedNoteAction.AddNote(title = title, content = content))
                    } else {
                        println("  Usage: /add <title> | <content>")
                    }
                }

                trimmed.startsWith("/edit ", ignoreCase = true) -> {
                    val rest = trimmed.substring("/edit ".length).trim()
                    val spaceIdx = rest.indexOf(' ')
                    if (spaceIdx > 0) {
                        val noteId = rest.substring(0, spaceIdx)
                        val remainder = rest.substring(spaceIdx + 1).trim()
                        val parts = remainder.split("|", limit = 2).map { it.trim() }
                        val title = parts[0]
                        val content = parts.getOrElse(1) { "" }
                        store.dispatch(SharedNoteAction.EditNote(noteId = noteId, title = title, content = content))
                    } else {
                        println("  Usage: /edit <noteId> <title> | <content>")
                    }
                }

                trimmed.startsWith("/delete ", ignoreCase = true) -> {
                    val noteId = trimmed.substring("/delete ".length).trim()
                    if (noteId.isNotEmpty()) {
                        store.dispatch(SharedNoteAction.DeleteNote(noteId = noteId))
                    } else {
                        println("  Usage: /delete <noteId>")
                    }
                }

                trimmed.startsWith("/") -> {
                    println("  Unknown command. Type /quit to exit.")
                }

                else -> {
                    println("  Unknown input. Use /add, /edit, /delete, /list, /devices, or /quit.")
                }
            }
        }
    }

    // Cleanup
    println("\n  Disconnecting...")
    collectorJob.cancel()
    store.closeGracefully { dispatch ->
        dispatch(SharedNoteAction.DeviceDisconnected(deviceName))
        dispatch(ClientNoteAction.Disconnect)
    }

    println("  Bye!")
}

@Suppress("UNCHECKED_CAST")
private fun createNoteStore(userId: String): Store<ClientNoteState, NoteAction> {
    val connection = KtorWebSocketClientConnection.create(
        host = "localhost",
        port = 8080,
        path = "/sync/$userId",
    ).typedJson<SharedNoteAction>() as TypedClientConnection<NoteAction>

    return createClientStore(
        initialState = ClientNoteState(userId = userId),
        syncMiddleware = NoteRemoteMiddleware(connection),
        reducer = clientNoteReducer,
    )
}
