package io.flowdux.sample.multiplexer.client

import io.flowdux.remote.ktor.KtorWebSocketClientConnection
import io.flowdux.remote.multiplexer.ClientConnectionMultiplexer
import io.flowdux.remote.multiplexer.typedRoutedJson
import io.flowdux.sample.multiplexer.SharedChatAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Connection Multiplexer Demo Client
 *
 * This client demonstrates participating in multiple chat rooms over a single
 * WebSocket connection using [ClientConnectionMultiplexer].
 *
 * Commands:
 * - /join <room>  - Join a room
 * - /leave <room> - Leave a room
 * - /rooms        - List joined rooms
 * - /switch <room>- Switch active room
 * - /quit         - Disconnect and exit
 * - <message>     - Send message to active room
 */
fun main(args: Array<String>) = runBlocking {
    val username = args.firstOrNull() ?: promptUsername()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    println(
        """
            ╔══════════════════════════════════════════════════════════╗
            ║     FlowDux Connection Multiplexer Demo Client           ║
            ╠══════════════════════════════════════════════════════════╣
            ║  Single WebSocket, multiple rooms!                       ║
            ╠══════════════════════════════════════════════════════════╣
            ║  Commands:                                               ║
            ║    /join <room>   - Join a room                          ║
            ║    /leave <room>  - Leave a room                         ║
            ║    /rooms         - List joined rooms                    ║
            ║    /switch <room> - Switch active room                   ║
            ║    /quit          - Disconnect and exit                  ║
            ║    <message>      - Send message to active room          ║
            ╚══════════════════════════════════════════════════════════╝
        """.trimIndent(),
    )
    println()
    println("Welcome, $username!")

    // Create the physical connection
    val physicalConnection =
        KtorWebSocketClientConnection
            .create(
                host = "localhost",
                port = 8080,
                path = "/ws",
            ).typedRoutedJson<SharedChatAction>()

    // Create the multiplexer
    val multiplexer = ClientConnectionMultiplexer(physicalConnection, scope)

    // Room manager to track joined rooms and their stores
    val roomManager = ClientRoomManager(username, multiplexer, scope)

    try {
        // Connect to server
        println("[Client] Connecting to server...")
        multiplexer.connect()
        // Wait for connection to be established
        delay(500)
        println("[Client] Connected!")

        // Auto-join a default room
        val defaultRoom = "general"
        roomManager.joinRoom(defaultRoom)
        delay(300)

        println("\nYou are now in room [$defaultRoom]. Type a message or use /join <room> to join more rooms.")
        println()

        // Interactive command loop
        withContext(Dispatchers.IO) {
            while (true) {
                print("[${roomManager.activeRoom}] $username> ")
                val input = readlnOrNull()?.trim() ?: break

                when {
                    input.isEmpty() -> continue

                    input == "/quit" -> break

                    input == "/rooms" -> {
                        val rooms = roomManager.joinedRooms()
                        println("Joined rooms: ${rooms.joinToString(", ").ifEmpty { "(none)" }}")
                        println("Active room: ${roomManager.activeRoom}")
                    }

                    input.startsWith("/join ") -> {
                        val roomId = input.removePrefix("/join ").trim()
                        if (roomId.isNotEmpty()) {
                            roomManager.joinRoom(roomId)
                        } else {
                            println("Usage: /join <room>")
                        }
                    }

                    input.startsWith("/leave ") -> {
                        val roomId = input.removePrefix("/leave ").trim()
                        if (roomId.isNotEmpty()) {
                            roomManager.leaveRoom(roomId)
                        } else {
                            println("Usage: /leave <room>")
                        }
                    }

                    input.startsWith("/switch ") -> {
                        val roomId = input.removePrefix("/switch ").trim()
                        if (roomId.isNotEmpty()) {
                            roomManager.switchRoom(roomId)
                        } else {
                            println("Usage: /switch <room>")
                        }
                    }

                    input.startsWith("/") -> {
                        println("Unknown command: $input")
                        println("Commands: /join, /leave, /rooms, /switch, /quit")
                    }

                    else -> {
                        // Send message to active room
                        roomManager.sendMessage(input)
                    }
                }
            }
        }
    } finally {
        println("\n[Client] Disconnecting...")
        roomManager.leaveAllRooms()
        multiplexer.close()
        scope.cancel()
        println("[Client] Goodbye!")
    }
}

private fun promptUsername(): String {
    print("Enter your username: ")
    return readlnOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "User${(1..1000).random()}"
}
