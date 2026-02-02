# Building a Real-Time Chat Without REST: Action/State in Practice

*From concept to working code — designing a chat app with actions and state instead of endpoints*

*This is Part 2 of a 3-part series. [← Part 1: What if You Never Had to Design a REST API Again?](#) | [Part 3: Scaling Action/State →](#)*

> **Note:** This series is a *concept proposal*. It explores the Action/State model as an alternative communication pattern — not a universal replacement for REST. We're sharing an idea and inviting discussion.

---

In [Part 1](#), we proposed a radical simplification: instead of designing REST endpoints, design **Actions** (what users do) and **State** (what changes as a result). Real-time sync comes for free, the entire API contract fits in a single `sealed interface`, and you skip a whole category of design decisions — URLs, HTTP methods, status codes, DTOs.

Bold claim. Let's prove it by building something real.

In this post, we'll build a **multi-user real-time chat application** — first sketching how you'd do it with REST, then implementing it entirely with the Action/State model. We'll compare the two side by side.

---

## The Requirements

A basic chat app needs:

- Users can join a chat room
- Users can send messages
- Users can leave a room
- All connected users see new messages in real time
- All connected users see join/leave events in real time

Simple enough. But that last two requirements — "in real time" — are where REST starts to get complicated.

---

## How You'd Build This With REST

### Endpoint Design

```
POST   /api/v1/chat/join         { "user": "Alice" }
POST   /api/v1/chat/messages     { "user": "Alice", "text": "Hello" }
DELETE /api/v1/chat/leave         { "user": "Alice" }
GET    /api/v1/chat/messages     → paginated message list
GET    /api/v1/chat/users        → current user list
```

That's 5 endpoints. Each needs:
- A URL path
- An HTTP method
- Request/response DTOs
- Status codes (200, 201, 400, 404...)
- Error response format

### The Real-Time Problem

Those endpoints handle request-response. But the requirements say users should see messages *as they arrive*. That means you also need one of:

1. **Polling** — `GET /messages` every second. Wasteful and laggy.
2. **SSE** — Server-Sent Events for real-time. One-directional; client still uses REST for sending.
3. **WebSocket** — A second communication channel alongside REST.

Any of these means designing a second protocol. With WebSocket, you'd define event types like `"new_message"`, `"user_joined"`, `"user_left"` — a separate schema from your REST DTOs. Two contracts to maintain.

### Total Design Surface

For a "simple" chat app with REST + real-time:

- 5 REST endpoints
- 5 request/response DTO pairs
- 1 WebSocket channel with its own message format
- At least 3 event types on the WebSocket
- Authentication for both REST and WebSocket
- Error handling for both channels
- State reconciliation between REST responses and WebSocket events

That's a lot of surface area for a chat app.

---

## How We'll Build It With Action/State

### Step 1: Define the Contract

Everything starts here. What can users do? What does the state look like?

```kotlin
// ─── Shared module (client + server both see this) ───

// Action — what users can do
@Serializable
sealed interface SharedChatAction : ChatAction {
    // Client → Server
    @Serializable
    data class JoinRoom(val user: String) : SharedChatAction, ServerSharedAction

    @Serializable
    data class LeaveRoom(val user: String) : SharedChatAction, ServerSharedAction

    @Serializable
    data class SendMessage(val user: String, val text: String) : SharedChatAction, ServerSharedAction

    // Server → Client
    @Serializable
    data class SyncState(val state: ChatState) : SharedChatAction, ClientSharedAction
}

// State — what the world looks like
@Serializable
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
)

@Serializable
data class ChatMessage(val user: String, val text: String)

@Serializable
sealed interface ChatEvent {
    @Serializable data class UserJoined(val user: String) : ChatEvent
    @Serializable data class UserLeft(val user: String) : ChatEvent
    @Serializable data class MessageReceived(val user: String, val text: String) : ChatEvent
}
```

That's the entire API. Three actions the client can send (`JoinRoom`, `LeaveRoom`, `SendMessage`), one action the server sends back (`SyncState`), and the state structure. The `ServerSharedAction` and `ClientSharedAction` markers encode the direction in the type system — try sending a `SyncState` from the client and the compiler stops you.

Notice what's *missing*: URLs, HTTP methods, status codes, pagination parameters, separate request/response types. The design space collapsed.

### Step 2: Server Implementation

The server has its own internal state that can contain more than what it exposes to clients:

```kotlin
// ─── Server module ───

// Server-internal actions (never cross the wire)
sealed interface ServerChatAction : ChatAction {
    data class MessageReceived(val user: String, val text: String) : ServerChatAction
    data class UserJoined(val user: String) : ServerChatAction
    data class UserLeft(val user: String) : ServerChatAction
}

// Server state — has extra fields clients don't see
data class ServerChatState(
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
    val totalMessagesProcessed: Int = 0,  // server-only metric
) : State
```

The reducer handles server-internal actions:

```kotlin
val serverChatReducer: Reducer<ServerChatState, ChatAction> = buildReducer {
    on<ServerChatAction.MessageReceived> { state, action ->
        state.copy(
            messages = state.messages + ChatMessage(action.user, action.text),
            lastEvent = ChatEvent.MessageReceived(action.user, action.text),
            totalMessagesProcessed = state.totalMessagesProcessed + 1,
        )
    }
    on<ServerChatAction.UserJoined> { state, action ->
        state.copy(
            users = state.users + action.user,
            lastEvent = ChatEvent.UserJoined(action.user),
        )
    }
    on<ServerChatAction.UserLeft> { state, action ->
        state.copy(
            users = state.users - action.user,
            lastEvent = ChatEvent.UserLeft(action.user),
        )
    }
}
```

The middleware translates incoming shared actions into server-internal actions:

```kotlin
class ChatServerRemoteMiddleware(
    connection: TypedServerConnection<ChatAction>,
) : ServerRemoteMiddleware<ServerChatState, ChatAction>(connection) {

    override val processors = buildProcessors {
        on<SharedChatAction.SendMessage> { _, action ->
            emit(ServerChatAction.MessageReceived(action.user, action.text))
        }
        on<SharedChatAction.JoinRoom> { _, action ->
            emit(ServerChatAction.UserJoined(action.user))
        }
        on<SharedChatAction.LeaveRoom> { _, action ->
            emit(ServerChatAction.UserLeft(action.user))
        }
    }
}
```

And the server entry point — this is where it all comes together:

```kotlin
fun main() {
    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            webSocket("/chat") {
                println("[Server] Client connected")

                createChatStore(this).serve { serverState ->
                    SharedChatAction.SyncState(
                        ChatState(
                            messages = serverState.messages,
                            users = serverState.users,
                            lastEvent = serverState.lastEvent,
                        )
                    )
                }
            }
        }
    }.start(wait = true)
}
```

The `.serve { }` block is the key. It does two things:
1. Listens for incoming actions from the client and dispatches them to the store
2. Observes state changes and pushes them to the client via the mapping function

The mapping function selectively exposes server state — `totalMessagesProcessed` is server-internal and never sent to the client. The client only sees what the `ChatState` in `SyncState` contains.

### Step 3: Client Implementation

The client has its own state with client-local fields:

```kotlin
// ─── Client module ───

// Client-only actions (never cross the wire)
sealed interface ClientChatAction : ChatAction {
    data object Connect : ClientChatAction
    data object Disconnect : ClientChatAction
    data class SetCurrentUser(val user: String) : ClientChatAction
}

// Client state — synced fields + local fields
data class ClientChatState(
    // Synced from server
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
    // Client-local
    val currentUser: String = "",
) : State
```

The client reducer applies synced state and local actions:

```kotlin
val clientChatReducer: Reducer<ClientChatState, ChatAction> = buildReducer {
    on<SharedChatAction.SyncState> { state, action ->
        state.copy(
            messages = action.state.messages,
            users = action.state.users,
            lastEvent = action.state.lastEvent,
        )
    }
    on<ClientChatAction.SetCurrentUser> { state, action ->
        state.copy(currentUser = action.user)
    }
}
```

The client middleware handles connection lifecycle:

```kotlin
class ChatRemoteMiddleware(
    connection: TypedClientConnection<ChatAction>,
) : ClientRemoteMiddleware<ClientChatState, ChatAction>(connection) {

    override val processors = buildProcessors {
        on<ClientChatAction.Connect> { _, _ ->
            startConnection()
        }
        on<ClientChatAction.Disconnect> { _, _ ->
            stopConnection()
        }
    }
}
```

And using it:

```kotlin
fun main() = runBlocking {
    val store = createChatStore()

    // Observe state changes
    launch {
        store.state.collect { state ->
            when (val event = state.lastEvent) {
                is ChatEvent.UserJoined -> println("[System] ${event.user} joined")
                is ChatEvent.UserLeft -> println("[System] ${event.user} left")
                is ChatEvent.MessageReceived -> println("[${event.user}] ${event.text}")
                null -> {}
            }
        }
    }

    // Connect and interact
    store.dispatch(ClientChatAction.SetCurrentUser("Alice"))
    store.dispatch(ClientChatAction.Connect)
    store.dispatch(SharedChatAction.JoinRoom("Alice"))
    store.dispatch(SharedChatAction.SendMessage("Alice", "Hello everyone!"))
}
```

Look at the client code. It dispatches `SharedChatAction.JoinRoom` — a shared action. The `ClientRemoteMiddleware` intercepts it (because it's a `ServerSharedAction`), serializes it, and sends it over the WebSocket. On the server side, `ServerRemoteMiddleware` receives it, dispatches it to the server store, the middleware processes it, the reducer updates state, and `.serve {}` pushes `SyncState` back to all clients.

All of that happens from a single `store.dispatch()` call. No HTTP request. No endpoint URL. No status code handling.

---

## The Flow, Step by Step

Let's trace the complete lifecycle of a single action — Alice sends a message:

```
  Alice's Client                    Server                    Bob's Client
       │                              │                            │
  1.   │  dispatch(SendMessage(       │                            │
       │    "Alice", "Hello"))        │                            │
       │                              │                            │
  2.   │  ClientRemoteMiddleware      │                            │
       │  intercepts ServerSharedAction│                            │
       │  → serializes → WebSocket    │                            │
       │                              │                            │
  3.   │ ──── SendMessage ──────────→ │                            │
       │                              │                            │
  4.   │                  ServerRemoteMiddleware                    │
       │                  receives & dispatches                    │
       │                              │                            │
  5.   │                  ChatServerRemoteMiddleware                │
       │                  emits MessageReceived                    │
       │                              │                            │
  6.   │                  Reducer updates state:                   │
       │                  messages += ChatMessage("Alice","Hello") │
       │                              │                            │
  7.   │                  serve {} detects state change            │
       │                  maps ServerChatState → SyncState         │
       │                              │                            │
  8.   │ ←── SyncState(newState) ──── │ ── SyncState(newState) ──→ │
       │                              │                            │
  9.   │  ClientRemoteMiddleware      │    ClientRemoteMiddleware  │
       │  receives ClientSharedAction │    receives same action    │
       │  → dispatches to client store│    → dispatches to store   │
       │                              │                            │
  10.  │  Reducer applies SyncState   │    Reducer applies same    │
       │  → UI updates               │    → UI updates            │
```

Both clients end up with identical state. Alice sees her own message. Bob sees it too. No polling. No separate subscription. The same pipeline handles everything.

---

## Side-by-Side Comparison

### Design Artifacts

| Aspect | REST + WebSocket | Action/State |
|--------|-----------------|-------------|
| Endpoint definitions | 5 REST endpoints | 0 |
| DTO types | 10+ (request + response pairs) | 1 shared sealed interface |
| WebSocket event types | 3+ (separate from REST) | 0 (same channel) |
| Authentication points | 2 (REST + WS) | 1 (WS connection) |
| State reconciliation | Manual (merge REST + WS data) | Automatic (single state source) |
| Real-time setup | Separate channel, separate protocol | Built-in (same dispatch mechanism) |

### Code Volume

The REST approach requires:
- Route definitions for each endpoint
- Request/response serialization classes
- Controller/handler per endpoint
- WebSocket event handlers (separate)
- Client-side HTTP client configuration
- Client-side WebSocket client configuration
- State merging logic (REST response + WS events)

The Action/State approach requires:
- Shared action sealed interface
- Server reducer + middleware
- Client reducer + middleware
- Server `serve {}` call
- Client `dispatch()` calls

The core difference: REST requires you to build and maintain *two parallel communication channels* (HTTP + WebSocket) with *two separate contracts*. Action/State uses *one channel* with *one contract*.

### What You Don't Write

With Action/State, you skip writing:
- URL routing configuration
- HTTP method handlers
- Status code mapping
- Request validation middleware (per endpoint)
- Response serialization (per endpoint)
- WebSocket event type definitions
- Event-to-state reconciliation logic
- Polling logic or SSE setup

---

## Multi-Client: One Store, Many Connections

The simple example above creates a new Store per connection. For a real chat app, you want a *shared* Store where all clients contribute to the same state. Here's how the server changes:

```kotlin
fun main() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // One server for ALL clients — shared state
    val server = createRemoteServer(
        initialState = ServerChatState(),
        reducer = serverChatReducer,
        processors = chatProcessors(),
        stateMapper = { serverState ->
            SharedChatAction.SyncState(
                ChatState(
                    messages = serverState.messages,
                    users = serverState.users,
                    lastEvent = serverState.lastEvent,
                )
            )
        },
        scope = applicationScope,
    )

    embeddedServer(CIO, port = 8080) {
        install(WebSockets)
        routing {
            webSocket("/chat") {
                val sessionId = UUID.randomUUID().toString()
                val connection = KtorWebSocketServerConnection(this)
                    .typedJson<SharedChatAction>()

                server.handleClient(sessionId, connection)
            }
        }
    }.start(wait = true)
}
```

`createRemoteServer` creates a `RemoteServer` facade that combines a single Store, a session registry, and state broadcasting. Each WebSocket connection calls `handleClient`, which registers the connection for both receiving actions and broadcasting state updates. When any client dispatches an action, the Store processes it and broadcasts the new state to *all* connected clients.

The client code doesn't change at all. It still dispatches actions and receives state updates. It doesn't know or care whether the server has one client or a thousand.

---

## What About Errors?

A realistic chat app needs to handle errors. With the Action/State model, errors are just state:

```kotlin
// Add to shared contract
@Serializable
data class ActionError(
    val message: String,
    val code: String,
) : SharedChatAction, ClientSharedAction

// Server middleware validates and emits error if needed
on<SharedChatAction.SendMessage> { state, action ->
    if (action.text.isBlank()) {
        emit(ActionError("Message cannot be empty", "EMPTY_MESSAGE"))
        return@on
    }
    if (action.user !in state.users) {
        emit(ActionError("You must join the room first", "NOT_IN_ROOM"))
        return@on
    }
    emit(ServerChatAction.MessageReceived(action.user, action.text))
}
```

The client handles errors the same way it handles any other server action — through its reducer:

```kotlin
on<ActionError> { state, action ->
    state.copy(error = action)
}
```

No status codes to memorize. No error response format to design separately. Errors are part of the same typed contract.

---

## A Note on flowdux-remote

All the code in this post uses [flowdux-remote](https://github.com/user/flowdux), a Kotlin library we built as a reference implementation of the Action/State pattern. It provides:

- `ServerRemoteMiddleware` / `ClientRemoteMiddleware` — handle serialization and WebSocket transport
- `serve {}` — observe state changes and push to clients
- `createRemoteServer` — create a `RemoteServer` facade managing multiple client connections to a shared Store
- `TypedConnection` — type-safe abstraction over the wire protocol
- `ActionCodec` — pluggable serialization (JSON via `kotlinx.serialization` included)

The library is built on Kotlin Multiplatform and Ktor for WebSocket transport. But the pattern itself is language- and framework-agnostic. If you're using TypeScript, Swift, or any language with algebraic types and WebSocket support, you could implement the same architecture.

---

## What's Next

We've built a working chat app without a single REST endpoint. One shared contract. One communication channel. Real-time by default.

But this was a single room with a single shared state. Real applications need:
- Multiple rooms, each with independent state
- Player-specific state views (e.g., in a game, you shouldn't see the opponent's hidden cards)
- High-frequency state updates batched efficiently (game servers at 60fps)
- Horizontal scaling across multiple server instances

In **Part 3**, we'll tackle all of these. We'll scale the Action/State model from a chat room to a multiplayer game server, introducing Room management, StateView filtering, tick-based batching, and Redis-backed horizontal scaling.

---

*This is Part 2 of a 3-part series. [← Part 1: What if You Never Had to Design a REST API Again?](#) | [Part 3: Scaling Action/State →](#)*

*[Follow me on Medium](#) for Part 3.*
