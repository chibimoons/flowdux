# Remote State Sync (WebSocket)

FlowDux Remote enables real-time client-server state synchronization over WebSocket.

## Architecture

```mermaid
flowchart LR
    subgraph Client
        CS["Store"] --> CRM["ClientRemote<br/>Middleware"]
    end

    CRM -- "ServerSharedAction<br/>(encode → send)" --> WS(("WebSocket"))
    WS -- "ClientSharedAction<br/>(receive → decode)" --> CRM

    subgraph Server
        SRM["ServerRemote<br/>Middleware"] --> SS["Store"]
    end

    WS -- "ServerSharedAction<br/>(receive → decode)" --> SRM
    SRM -- "ClientSharedAction<br/>(encode → send)" --> WS
```

| Component | Role |
|-----------|------|
| **ServerSharedAction** | Client → Server action marker (intercepted by CRM, sent over wire) |
| **ClientSharedAction** | Server → Client action marker (intercepted by SRM, sent over wire) |
| **ClientRemoteMiddleware** | Intercepts `ServerSharedAction`s, sends to server; listens for server messages |
| **ServerRemoteMiddleware** | Intercepts `ClientSharedAction`s, sends to client; listens for client messages |
| **TypedConnection** | Type-safe transport abstraction (encode/decode via `ActionCodec`) |
| **ActionCodec** | Serialization interface (`kotlinx.serialization` binding provided) |

## Installation

Add the relevant modules to your `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            // Shared action markers (ServerSharedAction, ClientSharedAction)
            implementation("io.github.chibimoons:flowdux-remote-core:1.12.0")
            // Client middleware (ClientRemoteMiddleware)
            implementation("io.github.chibimoons:flowdux-remote-client:1.12.0")
            // Server middleware (ServerRemoteMiddleware)
            implementation("io.github.chibimoons:flowdux-remote-server:1.12.0")
            // kotlinx.serialization codecs (ActionCodec, MessageCodec)
            implementation("io.github.chibimoons:flowdux-remote-serialization:1.12.0")
            // Ktor WebSocket transport (JVM, iOS, JS — WASM not supported)
            implementation("io.github.chibimoons:flowdux-remote-ktor:1.12.0")
        }
    }
}
```

## Version Compatibility

FlowDux remote modules require matching Kotlin and serialization versions:

| Dependency | Version |
|------------|---------|
| Kotlin | 2.2.10 |
| kotlinx-serialization-json | 1.7.3 |

```kotlin
plugins {
    kotlin("multiplatform") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
    }
}
```

**Important:** Using different Kotlin versions between your project and FlowDux may cause compilation errors on JS/iOS targets due to metadata incompatibility.

## 1. Define Shared Actions

Actions shared between client and server use direction markers.

### Recommended Pattern

Define a `@Serializable sealed interface` for your shared actions, with each variant implementing the appropriate direction marker:

```kotlin
import io.flowdux.Action
import io.flowdux.remote.ServerSharedAction
import io.flowdux.remote.ClientSharedAction
import kotlinx.serialization.Serializable

// Base action interface for your domain
sealed interface ChatAction : Action

// Shared actions with serialization support
@Serializable
sealed interface SharedChatAction : ChatAction {
    // Client → Server: implement ServerSharedAction
    @Serializable
    data class SendMessage(val text: String) : SharedChatAction, ServerSharedAction

    @Serializable
    data class JoinRoom(val user: String) : SharedChatAction, ServerSharedAction

    // Server → Client: implement ClientSharedAction
    @Serializable
    data class MessageReceived(val from: String, val text: String) : SharedChatAction, ClientSharedAction

    @Serializable
    data class SyncState(val state: ChatState) : SharedChatAction, ClientSharedAction
}

// Local-only actions (not sent over network)
sealed interface LocalChatAction : ChatAction {
    data object Connect : LocalChatAction
    data object Disconnect : LocalChatAction
    data class SetError(val message: String) : LocalChatAction
}
```

### Key Points

1. **`@Serializable` on sealed interface** — Required for polymorphic serialization
2. **`@Serializable` on each variant** — Each data class needs its own annotation
3. **Direction markers** — `ServerSharedAction` for client→server, `ClientSharedAction` for server→client
4. **Local actions** — Actions not shared don't need serialization or markers

## 2. Server Setup

```kotlin
webSocket("/chat") {
    val typedConnection = KtorWebSocketServerConnection(this)
        .typedJson<SharedChatAction>() as TypedServerConnection<ChatAction>

    createStore(
        initialState = ServerChatState(),
        reducer = serverChatReducer,
        middlewares = listOf(ChatServerRemoteMiddleware(typedConnection)),
    ).serve { serverState ->
        SharedChatAction.SyncState(serverState.toChatState())
    }
}
```

`serve()` handles client message listening, state synchronization, and store cleanup automatically.

## 3. Client Setup

```kotlin
val connection = KtorWebSocketClientConnection.create(
    host = "localhost", port = 8080, path = "/chat",
).typedJson<SharedChatAction>() as TypedClientConnection<ChatAction>

val store = createStore(
    initialState = ClientChatState(),
    reducer = clientChatReducer,
    middlewares = listOf(ChatRemoteMiddleware(connection)),
)

// Connect and interact
store.dispatch(ClientChatAction.Connect)
store.dispatch(SharedChatAction.SendMessage("Hello!"))
```

See `kotlin/samples/remote-chat` for the complete working example.
