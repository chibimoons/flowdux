package io.flowdux.sample.note.multidevice.server

import io.flowdux.Middleware
import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.serialization.typedJson
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.pattern.createSharedStateRoomServer
import io.flowdux.sample.note.NoteAction
import io.flowdux.sample.note.NoteState
import io.flowdux.sample.note.SharedNoteAction
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Multi-Device Notes Server Demo
 *
 * This sample demonstrates using the Room pattern for multi-device session sync.
 * The key insight: **roomId = userId** — all devices of the same user join the same room
 * and share state automatically.
 *
 * Endpoints:
 * - GET /users — List active user sessions
 * - WS /sync/{userId} — Connect a device to a user's note session
 */
fun main() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Room Server where roomId = userId
    // Each user gets their own independent room with shared notes state
    val roomServer = createSharedStateRoomServer(
        initialStateFactory = { userId ->
            println("[Server] Creating session for user: $userId")
            ServerNoteState(userId = userId)
        },
        reducer = serverNoteReducer,
        processors = noteProcessors(),
        stateMapper = { state ->
            println(
                "[User ${state.userId}] State changed: devices=${state.connectedDevices}, notes=${state.notes.size}",
            )
            SharedNoteAction.SyncState(
                NoteState(
                    notes = state.notes,
                    connectedDevices = state.connectedDevices,
                    lastEvent = state.lastEvent,
                ),
            )
        },
        scope = applicationScope,
    )

    // Periodic cleanup of empty rooms (users with no connected devices)
    applicationScope.launch {
        while (isActive) {
            delay(60_000)
            val destroyed = roomServer.cleanupEmptyRooms()
            if (destroyed.isNotEmpty()) {
                println("[Server] Cleaned up sessions for users: $destroyed")
            }
        }
    }

    // Periodic status logging
    applicationScope.launch {
        while (isActive) {
            delay(15_000)
            printStatus(roomServer)
        }
    }

    println(
        """
        ╔══════════════════════════════════════════════════════╗
        ║     FlowDux Multi-Device Notes Server                ║
        ╠══════════════════════════════════════════════════════╣
        ║  Pattern: Room (roomId = userId)                     ║
        ║                                                      ║
        ║  Endpoints:                                          ║
        ║    GET  /users          - List active user sessions  ║
        ║    WS   /sync/{userId}  - Connect device to session  ║
        ╠══════════════════════════════════════════════════════╣
        ║  Same userId = same room = shared notes!             ║
        ╚══════════════════════════════════════════════════════╝
        """.trimIndent(),
    )
    println()

    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            get("/users") {
                val userIds = roomServer.roomIds()
                call.respondText(
                    "Active user sessions (${userIds.size}): ${userIds.joinToString(", ").ifEmpty {
                        "(none)"
                    }}",
                )
            }

            // userId as roomId — all devices of the same user connect here
            webSocket("/sync/{userId}") {
                val userId = call.parameters["userId"]
                if (userId.isNullOrBlank()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "User ID required"))
                    return@webSocket
                }

                val room = roomServer.getOrCreateRoom(userId)
                val sessionId = UUID.randomUUID().toString()

                @Suppress("UNCHECKED_CAST")
                val connection = KtorWebSocketServerConnection(this)
                    .typedJson<SharedNoteAction>() as TypedServerConnection<NoteAction>

                println("[Server] [$userId] Device $sessionId connected")

                try {
                    room.handleClient(sessionId, connection)
                } finally {
                    println("[Server] [$userId] Device $sessionId disconnected")
                    roomServer.destroyRoomIfEmpty(userId)
                }
            }
        }
    }.start(wait = true)

    roomServer.close()
}

private suspend fun printStatus(roomServer: io.flowdux.remote.server.pattern.RoomServer<*>) {
    val userIds = roomServer.roomIds()
    println("\n=== User Session Status ===")
    if (userIds.isEmpty()) {
        println("No active sessions")
    } else {
        userIds.forEach { userId ->
            @Suppress("UNCHECKED_CAST")
            val room = roomServer.getRoom(
                userId,
            ) as? io.flowdux.remote.server.pattern.SharedStateServer<ServerNoteState, NoteAction>
            room?.let {
                val state = it.currentState
                println(
                    "  [$userId] devices=${state.connectedDevices}, notes=${state.notes.size}, edits=${state.totalEdits}",
                )
            }
        }
    }
    println("===========================\n")
}

private fun noteProcessors() = Middleware.ActionProcessorBuilder<ServerNoteState, NoteAction>().apply {
    on<SharedNoteAction.AddNote> { _, action ->
        val noteId = UUID.randomUUID().toString().take(8)
        emit(ServerNoteAction.NoteAdded(id = noteId, title = action.title, content = action.content))
    }
    on<SharedNoteAction.DeleteNote> { _, action ->
        emit(ServerNoteAction.NoteDeleted(noteId = action.noteId))
    }
    on<SharedNoteAction.EditNote> { _, action ->
        emit(ServerNoteAction.NoteEdited(noteId = action.noteId, title = action.title, content = action.content))
    }
    on<SharedNoteAction.DeviceConnected> { _, action ->
        emit(ServerNoteAction.DeviceJoined(deviceName = action.deviceName))
    }
    on<SharedNoteAction.DeviceDisconnected> { _, action ->
        emit(ServerNoteAction.DeviceLeft(deviceName = action.deviceName))
    }
}.build()
