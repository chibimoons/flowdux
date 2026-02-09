# Scaling Action/State: From Single Session to Multiplayer Game Server

*Rooms, state views, tick batching, and horizontal scaling for the Action/State model*

*This is Part 3 of a 3-part series. [← Part 1: What if You Never Had to Design a REST API Again?](./part1-concept-en.md) | [← Part 2: Building a Real-Time Chat Without REST](./part2-practice-en.md)*

> **Note:** This series is a *concept proposal*. It explores the Action/State model as an alternative communication pattern — not a universal replacement for REST. We're sharing an idea and inviting discussion.

---

In [Part 1](./part1-concept-en.md), we proposed replacing REST endpoints with **Actions** and **State**. In [Part 2](./part2-practice-en.md), we built a real-time chat application to prove it works. One shared contract, one communication channel, real-time by default.

But that was a single chat room with all users sharing the same state. Real applications are messier:

- A game has multiple rooms, each with independent state
- Players in the same game might see *different* things (fog of war, hidden hands)
- A game server ticking at 60fps can't send a WebSocket message on every state change
- A successful app runs on more than one server

In this post, we'll push the Action/State model to its limits and see how far it scales — from chat rooms to multiplayer game servers.

---

## The Room Pattern: One Room = One Store

The single-Store chat from Part 2 works fine for one room. But what about hundreds of concurrent rooms, each with their own state?

The answer is straightforward: **one Room equals one Store**. Each Room is an isolated state container with its own middleware pipeline.

```
                        ┌──────────────────────────┐
                        │       Room Manager        │
                        │  (creates/destroys rooms) │
                        └─────┬──────┬──────┬───────┘
                              │      │      │
                    ┌─────────┴──┐ ┌─┴──────┴─────┐
                    │   Room A   │ │    Room B     │
                    │            │ │               │
                    │  Store     │ │  Store        │
                    │  (State A) │ │  (State B)    │
                    │            │ │               │
                    │  Middleware │ │  Middleware   │
                    │  Pipeline  │ │  Pipeline     │
                    │            │ │               │
                    │  Players:  │ │  Players:     │
                    │  Alice,Bob │ │  Carol,Dave   │
                    └────────────┘ └───────────────┘
```

Alice and Bob's actions only affect Room A's state. Carol and Dave only affect Room B. The states are completely isolated. Each Store runs its own middleware pipeline independently.

### Room Manager

The Room Manager is *not* part of FlowDux — it's plain server code that handles room creation, destruction, and player-to-room mapping:

```kotlin
class Room(
    val id: String,
    val maxPlayers: Int = 4,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val players = ConcurrentHashMap<String, WebSocketSession>()

    // FlowDux territory ─────────────────────────────
    private lateinit var store: Store<GameState, GameAction>

    fun initialize(connection: TypedServerConnection<GameAction>) {
        store = createStore(
            initialState = GameState(),
            reducer = gameReducer,
            middlewares = listOf(
                ValidationMiddleware(),
                GameLogicMiddleware(),
                GameSingleClientSyncMiddleware(connection),
            ),
            scope = scope,
        )
    }
    // ────────────────────────────────────────────────

    val playerCount: Int get() = players.size
    val isFull: Boolean get() = players.size >= maxPlayers

    fun addPlayer(playerId: String, session: WebSocketSession) {
        players[playerId] = session
    }

    fun removePlayer(playerId: String) {
        players.remove(playerId)
    }

    fun dispatch(action: GameAction) = store.dispatch(action)

    fun close() {
        store.close()
        players.clear()
    }
}

class RoomManager {
    private val rooms = ConcurrentHashMap<String, Room>()
    private val playerRoomMap = ConcurrentHashMap<String, String>()

    fun findOrCreateRoom(playerId: String): Room {
        playerRoomMap[playerId]?.let { roomId ->
            rooms[roomId]?.let { return it }
        }

        val available = rooms.values.firstOrNull { !it.isFull }
        if (available != null) {
            playerRoomMap[playerId] = available.id
            return available
        }

        val room = Room(id = "room-${rooms.size + 1}")
        rooms[room.id] = room
        playerRoomMap[playerId] = room.id
        return room
    }

    fun removePlayer(playerId: String) {
        val roomId = playerRoomMap.remove(playerId) ?: return
        val room = rooms[roomId] ?: return
        room.removePlayer(playerId)
        if (room.playerCount == 0) {
            room.close()
            rooms.remove(roomId)
        }
    }
}
```

The boundary between FlowDux and server code is clear:

| Component | FlowDux? | Description |
|-----------|----------|-------------|
| Room Manager | No | Creates/destroys rooms, matches players |
| Auth Middleware | No | Ktor JWT validation |
| WebSocket Routing | No | Ktor routing |
| **Store (per Room)** | **Yes** | State management |
| **Middleware Pipeline** | **Yes** | Validation, game logic |
| **SingleClientSyncMiddleware** | **Yes** | Client ↔ Store bridge |

FlowDux manages the state and business logic *inside* each Room. Everything outside — authentication, matchmaking, connection management — is your server code.

---

## The Middleware Pipeline: Composable Server Logic

One of the strengths of the Action/State model is that server logic is a pipeline of middleware, each handling one concern. For a game server, the pipeline might look like:

```
Incoming Action
    │
    ▼
┌───────────────────┐
│ 1. Validation     │  Is the action structurally valid?
│    (bounds check) │  Is the move within the map?
└───────┬───────────┘
        │
        ▼
┌───────────────────┐
│ 2. Auth Check     │  Does this player have permission
│    (authorization)│  to perform this action?
└───────┬───────────┘
        │
        ▼
┌───────────────────┐
│ 3. Game Logic     │  Process the action: collision detection,
│    (core rules)   │  scoring, physics simulation
└───────┬───────────┘
        │
        ▼
┌───────────────────┐
│ 4. Remote         │  Sync state to connected clients
│    (broadcast)    │
└───────────────────┘
```

First, let's define the game's Action and State contract — the same pattern from Parts 1 and 2, now for a game:

```kotlin
// ─── Shared module ───

// State — what the game world looks like
data class GameState(
    val players: Map<String, Player> = emptyMap(),
    val projectiles: List<Projectile> = emptyList(),
    val tick: Long = 0,
) : State

data class Player(val id: String, val x: Float, val y: Float, val hp: Int = 100)
data class Projectile(val ownerId: String, val x: Float, val y: Float, val dx: Float, val dy: Float)

// Action — what players can do
sealed interface GameAction : Action {
    // Client → Server
    data class Move(val playerId: String, val x: Float, val y: Float) : GameAction, ServerSharedAction
    data class Shoot(val playerId: String, val dx: Float, val dy: Float) : GameAction, ServerSharedAction
    data class JoinGame(val playerId: String) : GameAction, ServerSharedAction

    // Server → Client
    data class PlayerMoved(val playerId: String, val x: Float, val y: Float) : GameAction, ClientSharedAction
    data class ProjectileFired(val projectile: Projectile) : GameAction, ClientSharedAction
    data class PlayerJoined(val player: Player) : GameAction, ClientSharedAction
    data class PlayerHit(val playerId: String, val damage: Int) : GameAction, ClientSharedAction
}
```

Actions define what players can do (`Move`, `Shoot`, `JoinGame`). State defines the game world (`GameState` with players, projectiles, tick count). The server processes client actions and emits result actions that update the state and get pushed back to clients.

Each middleware is a separate, testable unit:

```kotlin
// Validation: clamp coordinates to valid range
class ValidationMiddleware : Middleware<GameState, GameAction> {
    override val name = "Validation"

    override val processors = buildProcessors {
        on<GameAction.Move> { _, action ->
            val clampedX = action.x.coerceIn(0f, 1000f)
            val clampedY = action.y.coerceIn(0f, 1000f)
            emit(GameAction.PlayerMoved(action.playerId, clampedX, clampedY))
        }
    }
}

// Game logic: shooting creates a projectile from the player's position
class GameLogicMiddleware : Middleware<GameState, GameAction> {
    override val name = "GameLogic"

    override val processors = buildProcessors {
        on<GameAction.Shoot> { state, action ->
            val player = state.players[action.playerId] ?: return@on
            val projectile = Projectile(
                ownerId = action.playerId,
                x = player.x, y = player.y,
                dx = action.dx, dy = action.dy,
            )
            emit(GameAction.ProjectileFired(projectile))
        }
        on<GameAction.JoinGame> { _, action ->
            val player = Player(id = action.playerId, x = 500f, y = 500f)
            emit(GameAction.PlayerJoined(player))
        }
    }
}
```

Adding a new concern — say, logging or analytics — means adding one more middleware to the pipeline. Existing middleware don't change.

---

## StateView: Per-Player State Filtering

In the chat app, every user saw the same state. In many games, that's wrong. Consider:

- **Fog of war**: You only see enemies within your visibility range
- **Card games**: You see your own hand but not your opponent's
- **Asymmetric roles**: A dungeon master sees everything; players see their own perspective

This requires a **StateView** — a function that takes the full server state and a player ID, and returns the state that player is allowed to see.

```kotlin
// Conceptual StateView for a card game
fun stateViewFor(playerId: String, fullState: GameState): PlayerGameView {
    return PlayerGameView(
        myHand = fullState.hands[playerId] ?: emptyList(),
        opponentCardCount = fullState.hands
            .filterKeys { it != playerId }
            .mapValues { it.value.size },
        discardPile = fullState.discardPile,
        currentTurn = fullState.currentTurn,
        myScore = fullState.scores[playerId] ?: 0,
    )
}
```

Instead of broadcasting the same `SyncState` to everyone, the server creates a per-player view:

```
Full Server State
    │
    ├── stateViewFor("alice") → Alice sees her hand + opponent card counts
    ├── stateViewFor("bob")   → Bob sees his hand + opponent card counts
    └── stateViewFor("carol") → Carol sees her hand + opponent card counts
```

Each player gets a `SyncState` containing only the information they're allowed to see. The full state never leaves the server.

In flowdux-remote, `createSessionAwareRemoteServer` supports this directly — instead of one global `stateMapper`, you provide a `sessionStateMapper` that receives both the state and the session ID:

```kotlin
val server = createSessionAwareRemoteServer(
    initialState = GameState(),
    reducer = gameReducer,
    processors = gameProcessors(),
    sessionStateMapper = { state, sessionId ->
        SharedGameAction.SyncState(stateViewFor(sessionId, state))
    },
    scope = applicationScope,
)
```

The pattern remains the same: state changes trigger pushes. The only difference is that `sessionStateMapper` is called once per connected client, so each receives a filtered view. Return `null` to skip sending to a particular session.

---

## Tick Batching: Game Server Performance

A game server processing player actions at high frequency can't send a WebSocket message for every single state change. If 10 players each move 30 times per second, that's 300 state changes per second per player — sending all of them would saturate the connection.

The solution is **tick batching**: accumulate state changes over a fixed interval (a "tick") and send one consolidated update per tick.

```
Time ──────────────────────────────────────────────→

Actions:  Move Move Shoot Move Move Shoot Move
          ─────────┬──────────────────┬────────────
                   │                  │
          Tick 1 (16.67ms)    Tick 2 (16.67ms)
                   │                  │
                   ▼                  ▼
          Send consolidated    Send consolidated
          state snapshot       state snapshot
```

At 60 ticks per second (60fps), the server sends at most 60 state updates per second per player — regardless of how many actions were processed. The client receives a state snapshot that reflects all the actions processed during that tick.

```kotlin
// Conceptual tick loop
class TickBatcher(
    private val intervalMs: Long = 16L,  // ~60fps
    private val onTick: (GameState) -> Unit,
) {
    private var lastState: GameState? = null

    fun start(stateFlow: Flow<GameState>) = scope.launch {
        while (isActive) {
            delay(intervalMs)
            val currentState = stateFlow.value
            if (currentState != lastState) {
                onTick(currentState)
                lastState = currentState
            }
        }
    }
}
```

The tick loop is server-side infrastructure, not FlowDux itself. It sits between the Store's state output and the WebSocket broadcast. The Store processes actions immediately (game logic stays responsive), but the network output is batched.

Combined with StateView, the flow looks like:

```
Store state change
    │
    ▼
Tick Batcher (accumulates over 16.67ms)
    │
    ▼
StateView Filter (per-player)
    │
    ├── Player A filtered state → WebSocket → Client A
    ├── Player B filtered state → WebSocket → Client B
    └── Player C filtered state → WebSocket → Client C
```

---

## Horizontal Scaling: Multiple Server Instances

A single server can run many Rooms, but eventually you need multiple servers. The challenge: a player on Server 1 might be in a Room with a player on Server 2.

The standard approach is a **message backplane** — a shared bus (typically Redis Pub/Sub) that relays actions and state changes between servers.

```
                    ┌───────────────┐
                    │ Load Balancer │
                    │ (Sticky WS)   │
                    └───┬───────┬───┘
                        │       │
              ┌─────────┴──┐ ┌──┴──────────┐
              │  Server 1  │ │  Server 2   │
              │            │ │             │
              │  Room A    │ │  Room C     │
              │  Room B    │ │  Room D     │
              └─────┬──────┘ └──────┬──────┘
                    │               │
                    └───────┬───────┘
                            │
                    ┌───────┴───────┐
                    │  Redis Pub/Sub │
                    │  (Backplane)   │
                    └───────────────┘
```

### Sticky Sessions

WebSocket connections are inherently sticky — once established, all messages for that connection go to the same server. The load balancer routes new connections but doesn't interfere with existing ones. ALB, Nginx, and Envoy all handle this natively.

### Cross-Server Communication

When a Room needs to communicate with players on different servers (or when global events need to reach all servers), Redis Pub/Sub acts as the relay:

```kotlin
// Pseudocode for cross-server action relay
class RedisActionRelay(private val redis: RedisClient) {
    // Publish an action to all servers hosting this room
    suspend fun broadcastToRoom(roomId: String, action: GameAction) {
        redis.publish("room:$roomId:actions", serialize(action))
    }

    // Subscribe to actions for rooms on this server
    fun subscribeToRoom(roomId: String, onAction: (GameAction) -> Unit) {
        redis.subscribe("room:$roomId:actions") { message ->
            onAction(deserialize(message))
        }
    }
}
```

The Room itself doesn't know about Redis. From its perspective, actions arrive and state updates go out — whether the player is connected directly to this server or relayed through Redis is an infrastructure concern.

### What Redis Handles

| Concern | Redis Feature | Description |
|---------|--------------|-------------|
| Room-to-Room messaging | Pub/Sub | Relay actions between servers |
| Session storage | Key-Value | Map player → server for reconnection |
| Presence tracking | Sets + TTL | Track online players across servers |
| Matchmaking queue | Sorted Sets | Cross-server matchmaking |
| Leaderboards | Sorted Sets | Global rankings |

### What FlowDux Handles (unchanged)

| Concern | FlowDux Feature | Description |
|---------|-----------------|-------------|
| State management | Store | Per-room state |
| Business logic | Middleware | Validation, game rules |
| Client sync | SingleClientSyncMiddleware | Action receive + state push |
| State mapping | `serve {}` / StateView | Filter state per client |

The scaling layer wraps around FlowDux without changing it. The Store, middleware, and sync mechanisms work identically whether there's one server or fifty.

---

## Real-World Use Case Catalog

The Action/State model isn't limited to chat and games. Here's a catalog of applications where the pattern fits naturally, organized by domain:

### Gaming
| Use Case | Actions | State | Why Action/State |
|----------|---------|-------|-----------------|
| Multiplayer action game | Move, Shoot, UseItem | Player positions, health, inventory | Real-time state sync is the core requirement |
| Turn-based game | PlayCard, EndTurn, DrawCard | Board state, hands, scores | StateView hides opponent hands; turns are naturally action-based |
| Live quiz / trivia | SubmitAnswer, NextQuestion | Scores, current question, timer | All participants see same question; answers are actions |
| Interactive live streaming | SendReaction, Vote, Bid | Reaction counts, poll results, bid state | Thousands of viewers contributing to shared state |

### Collaboration
| Use Case | Actions | State | Why Action/State |
|----------|---------|-------|-----------------|
| Document editor | InsertText, DeleteRange, FormatText | Document content, cursor positions | Operational transform maps naturally to actions |
| Design tool | MoveElement, Resize, ChangeColor | Canvas state, selected elements | Multiple users editing shared canvas |
| Whiteboard | Draw, Erase, AddSticky | Board content, participants | Free-form collaboration with real-time sync |

### Business
| Use Case | Actions | State | Why Action/State |
|----------|---------|-------|-----------------|
| Live auction | PlaceBid, CloseLot | Current bid, bidder, timer, lot info | Time-critical shared state with many observers |
| Trading dashboard | PlaceOrder, CancelOrder | Portfolio, order book, prices | Real-time price updates + user actions |
| Order management | UpdateStatus, AssignDriver | Order states, driver locations | Dispatch + tracking in single state model |
| Ticket booking | SelectSeat, ConfirmBooking | Seat map, availability | Seat availability must be real-time to prevent double-booking |

### IoT & Monitoring
| Use Case | Actions | State | Why Action/State |
|----------|---------|-------|-----------------|
| Smart home | SetTemperature, TurnOnLight | Device states, sensor readings | Device commands are actions; sensor data is state |
| Industrial monitoring | AcknowledgeAlarm, SetThreshold | Sensor data, alarm states | Operators see shared dashboard; commands are actions |

### Workflow
| Use Case | Actions | State | Why Action/State |
|----------|---------|-------|-----------------|
| Approval workflow | Submit, Approve, Reject, Escalate | Document status, approval chain | State transitions map directly to actions |
| Kanban board | MoveCard, CreateCard, AssignMember | Board lanes, cards, assignments | Multi-user board with real-time updates |

### What These Have in Common

All of these share a pattern:
1. **Multiple users** affect **shared state**
2. Changes need to be **visible in real time**
3. The interaction model is **action-driven** (users do things; the world responds)

If your application fits this description, the Action/State model likely reduces your design complexity compared to REST + real-time bolt-on.

---

## Decision Matrix: When to Use What

| Factor | REST | Action/State |
|--------|------|-------------|
| Connection model | Stateless, per-request | Persistent WebSocket |
| Real-time needs | Requires add-on (WS/SSE/polling) | Built-in |
| API consumers | Many diverse clients, third parties | Known client applications |
| State sharing | Each request is independent | Shared mutable state |
| Interaction model | Resource-oriented (CRUD) | Behavior-oriented (actions) |
| Tooling ecosystem | Mature (Swagger, Postman, etc.) | Emerging |
| Discoverability | URLs are self-documenting | Requires shared type definitions |
| Caching | HTTP caching, CDN | Server pushes only on change |
| Offline support | Cache responses | Requires reconnection strategy |

The honest answer: if your application is a public API consumed by third-party developers, use REST. Its tooling, documentation ecosystem, and universal understanding are unmatched.

If your application is a product where you control both client and server, and it involves real-time shared state, the Action/State model removes an entire layer of complexity.

Many real-world applications need both: REST for public APIs and third-party integrations, Action/State for the real-time interactive core.

---

## The Architecture at Full Scale

Putting it all together — a multiplayer game server using the Action/State model at production scale:

```
Clients (Android, iOS, Web, Desktop)
    │
    │ WSS + JWT
    ▼
┌──────────────────────────┐
│     Load Balancer         │
│  (Nginx, Sticky Session)  │
└──────────┬───────────────┘
           │
    ┌──────┴──────┐
    ▼             ▼
┌────────┐  ┌────────┐
│Server 1│  │Server 2│
│        │  │        │
│ Auth   │  │ Auth   │  ← Ktor JWT (server code)
│  │     │  │  │     │
│  ▼     │  │  ▼     │
│ Room   │  │ Room   │  ← Room Manager (server code)
│ Manager│  │ Manager│
│  │     │  │  │     │
│  ├─Room A │  ├─Room C
│  │  │     │  │  │
│  │  Store │  │  Store    ← FlowDux
│  │  │     │  │  │
│  │  Middleware Pipeline   ← FlowDux
│  │  │ Validation
│  │  │ Auth Check
│  │  │ Game Logic
│  │  │ Remote Middleware   ← FlowDux
│  │  │     │  │  │
│  │  Tick  │  │  Tick     ← Server code
│  │  Batch │  │  Batch
│  │  │     │  │  │
│  │  State │  │  State    ← Per-player filtering
│  │  View  │  │  View
│  │        │  │        │
│  ├─Room B │  ├─Room D │
│  │  ...   │  │  ...   │
│        │  │        │
└────┬───┘  └────┬───┘
     │           │
     └─────┬─────┘
           │
    ┌──────┴──────┐
    │ Redis Cluster│
    │  Pub/Sub     │  ← Cross-server messaging
    │  Sessions    │  ← Reconnection routing
    │  Presence    │  ← Online tracking
    │  Matchmaking │  ← Queue management
    └──────────────┘
           │
    ┌──────┴──────┐
    │  Database    │
    │  Profiles    │
    │  History     │
    │  Rankings    │
    └─────────────┘
```

The FlowDux boundary is clear: Store, Middleware Pipeline, and Remote Middleware. Everything else — auth, room management, tick batching, state view filtering, Redis, databases — is your server code that wraps around the FlowDux core.

This separation is a feature. FlowDux handles what it's good at (state management, action processing, client sync). Your code handles what's domain-specific (matchmaking, game rules, persistence, scaling infrastructure).

---

## Conclusion: Resource-Oriented vs. Behavior-Oriented

REST models the world as **resources**: users, messages, rooms. You apply verbs (GET, POST, DELETE) to nouns (URLs). It's a powerful abstraction that has served us well for two decades.

The Action/State model views the world as **behaviors**: what users do, and what changes as a result. It doesn't model resources — it models interactions.

```
REST:     "What resources exist?"   → design URLs and methods
Action:   "What can users do?"      → design actions
State:    "What results from that?" → design state

REST:     GET /api/v1/game/42/players
Action:   dispatch(JoinGame("player-7"))
State:    GameState(players = [..., player7], ...)
```

Neither paradigm is universally better. But for **interactive, real-time, multi-user applications** — games, collaboration tools, live commerce, dashboards — the Action/State model offers:

1. **No API design overhead** — actions and state are the spec
2. **Real-time as default** — no add-on channels
3. **Type-safe contracts** — the compiler enforces the protocol
4. **Composable server logic** — middleware pipelines, not route handlers
5. **Natural scaling patterns** — Room = Store, StateView for per-client filtering, tick batching for performance

We built [flowdux-remote](https://github.com/user/flowdux) as a reference implementation in Kotlin. But the pattern transcends any particular library. If you're building an interactive application and find yourself designing REST endpoints only to bolt on WebSocket for real-time, consider whether Actions and State might be all you need.

---

*This is Part 3 of a 3-part series.*
*[← Part 1: What if You Never Had to Design a REST API Again?](#)*
*[← Part 2: Building a Real-Time Chat Without REST](#)*

*If this series sparked ideas or disagreements, I'd love to hear them. The best architectures emerge from debate, not dogma.*
