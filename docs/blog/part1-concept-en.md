# What if You Never Had to Design a REST API Again?

*Rethinking client-server communication with the Action/State model*

*This is Part 1 of a 3-part series. [Part 2: Building a Real-Time Chat Without REST →](#) | [Part 3: Scaling Action/State →](#)*

> **Note:** This series is a *concept proposal*. It introduces the Action/State model as an alternative pattern for client-server communication — not as a replacement for REST in every scenario. The goal is to share an architectural idea and invite discussion, not to prescribe a one-size-fits-all solution.

---

You know the drill. A new feature means a new API endpoint. You open your editor and start making decisions:

- What should the URL be? `/api/v1/chat/messages` or `/api/v1/messages`?
- `POST` or `PUT`?
- What status codes? `200`? `201`? `409` for conflicts?
- What goes in the request body? The response?
- Do you need pagination? Filtering? Sorting query params?
- Oh, and it needs to be real-time too — so add a WebSocket channel on the side.

Multiply that by every feature in your app. It's not that any single decision is hard. It's that there are *so many* of them, and they all compound. URL design, HTTP method semantics, status code taxonomy, DTO versioning, authentication headers, content negotiation — we've built an entire discipline around what is, fundamentally, "the client wants to do something, and the server should respond."

What if we stripped all of that away and just said what we mean?

---

## The Proposal: Actions and State

Here's the idea. Instead of designing REST endpoints, you design two things:

1. **Actions** — what can the user do?
2. **State** — what does the world look like after they do it?

That's it. No URLs. No HTTP methods. No status codes. No request/response DTOs. Just actions and state.

The client *dispatches* an action. The server processes it, updates its state, and *pushes* the new state back. Over a single persistent WebSocket connection.

Let's make this concrete. Consider a chat application. Here's what the REST approach looks like:

```
POST   /api/v1/chat/join       { "user": "Alice" }     → 200 { state }
GET    /api/v1/chat/messages                            → 200 { messages: [...] }
POST   /api/v1/chat/messages   { "user": "Alice", "text": "Hello" }  → 201 { message }
DELETE /api/v1/chat/leave       { "user": "Alice" }     → 200 { }
```

That's four endpoints, each with its own URL, method, request shape, and response shape. Now you also want real-time updates when *other* users send messages — so you bolt on a WebSocket or SSE channel. That's a fifth thing to design and maintain.

Here's the same thing with Action/State:

```
dispatch(JoinRoom("Alice"))        →  state update pushed
dispatch(SendMessage("Alice", "Hello"))  →  state update pushed
dispatch(LeaveRoom("Alice"))       →  state update pushed

GET /chat/messages?                →  unnecessary — it's already in State
```

The client dispatches actions. The server processes them and pushes the updated state. Real-time updates for other users? Already happening — every state change is pushed to all connected clients. There is no separate real-time channel to design.

---

## The API Spec Is a Single Interface

In REST, your API contract is scattered across URL patterns, OpenAPI specs, request/response types, and error schemas. In the Action/State model, the entire contract fits in one place:

```kotlin
// Action — what users can do
@Serializable
sealed interface SharedChatAction {
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

This is the entire API specification. The Actions define what users can do; the State defines what the world looks like as a result. Every action the client can send is a `ServerSharedAction`. Every response the server pushes back is a `ClientSharedAction`. The directions are encoded in the types. A shared module between client and server guarantees the contract at compile time — not at documentation time, not at integration-test time.

If you rename an action field, the client won't compile until it's updated. Try getting that from an OpenAPI spec.

---

## Three Benefits That Emerge Naturally

### 1. Real-time reactivity is a byproduct, not a feature

With REST, real-time is always an add-on. You design your endpoints, then realize you need live updates, then add WebSockets or SSE or polling on top. It's a second communication channel with its own message format and connection lifecycle.

With Action/State over a persistent connection, real-time is the default behavior. When Client A dispatches `SendMessage`, the server updates its state, and the new state is pushed to *all* connected clients — including Client B, who didn't ask for anything. There is no polling. There is no separate subscription. The same mechanism that handles request-response also handles broadcast.

```
  Client A                    Server                    Client B
     │                          │                          │
     │── SendMessage("Hi") ───→│                          │
     │                          │── state update ─────────→│
     │←── SyncState(newState) ──│                          │
```

### 2. Symmetric data flow on both sides

Both the client and the server follow the same unidirectional pattern:

```
dispatch(action) → middleware pipeline → reducer → new state
```

The server receives an action, runs it through its middleware (validation, business logic, persistence), reduces the state, and syncs it out. The client receives the synced state, runs it through its reducer, and updates the UI.

This structural symmetry means you can reason about both sides the same way. Server logic is a middleware pipeline. Client logic is a middleware pipeline. The mental model transfers.

### 3. Drastically reduced design surface

REST forces you to answer a large number of design questions per feature:

| REST | Action/State |
|------|-------------|
| URL path design | *(not needed)* |
| HTTP method selection | *(not needed)* |
| Status code mapping | *(not needed)* |
| Request DTO | Action fields |
| Response DTO | State shape |
| Pagination params | *(state contains the data)* |
| Auth header scheme | *(middleware handles it)* |
| Real-time channel | *(same channel)* |

You design the Action (what the user does) and the State (what the result looks like). Everything else is handled by the infrastructure.

---

## Addressing Common Concerns

These are the objections I hear most often. They deserve honest answers.

### "WebSocket load balancing is complex"

Not really. ALB, Nginx, and Envoy all support WebSocket connections natively. Once a connection is established, it's maintained — that's called a sticky session, and every major load balancer handles it out of the box. The operational overhead is comparable to managing HTTP keep-alive connections.

### "You lose the request-response correlation"

Consider what REST actually gives you here. You send `POST /join`, and the response comes back. But between your request and the response, another client might have changed the state. The response reflects the state *after* all those intermediate changes, not just your action.

The Action/State model is actually more honest about this. The state you receive always reflects *everything* that has happened, including other clients' actions. You never see a stale snapshot that pretends to be the result of just your request.

### "There's no standard error handling"

REST has status codes — `400`, `404`, `500`. But in practice, every API defines custom error response bodies anyway: `{ "code": "INVALID_NAME", "message": "..." }`. The `400 Bad Request` status code alone never tells you enough. So the work of modeling errors explicitly is identical in both approaches.

In the Action/State model, errors are modeled the same way as everything else — as part of the state, or as explicit error actions:

```kotlin
// Option 1: Error in state
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val error: ChatError? = null,  // null when no error
)

// Option 2: Error as action
data class ActionFailed(
    val originalAction: SharedChatAction,
    val reason: String
) : SharedChatAction, ClientSharedAction
```

### "What about file uploads? Caching?"

File uploads should go over HTTP. This isn't a limitation of the pattern — it's that HTTP is genuinely better for large binary transfers. Use HTTP for files, Action/State for app logic. The two coexist naturally.

As for caching: caching exists to avoid redundant requests. In a push model, the server sends state updates only when something changes. There are no redundant requests to cache. The problem caching solves doesn't exist here.

---

## When This Pattern Fits — and When It Doesn't

Let's be honest about the boundaries.

**Good fit:**
- Applications where users interact and the state changes frequently (chat, collaboration, gaming, dashboards)
- Any scenario where you'd eventually need real-time anyway
- Apps with a persistent connection (mobile apps, SPAs, desktop apps)
- Prototyping — you skip the entire API design phase and go straight to "what do users do?"

**Not a good fit:**
- Public APIs consumed by third parties (REST's discoverability and tooling ecosystem wins here)
- Simple CRUD with no real-time needs and many different consumers
- Environments where WebSocket connections can't be maintained (some corporate proxies, serverless platforms without WebSocket support)
- File-heavy workflows where most traffic is binary data

This is not a universal replacement for REST. It's an alternative paradigm that eliminates a large class of design decisions for applications where persistent connections are viable.

---

## A Reference Implementation

We've built a library called [flowdux-remote](https://github.com/user/flowdux) that implements this pattern in Kotlin. It provides:

- **Type-safe wire contracts** via `sealed interface` with directional markers (`ServerSharedAction` / `ClientSharedAction`)
- **Automatic state synchronization** — the server calls `serve {}` and state changes are pushed to clients
- **Middleware pipelines** on both sides for clean separation of concerns
- **Kotlin Multiplatform** support (Android, iOS, desktop, server)

The code example above (the `SharedChatAction` interface) is actual flowdux-remote code. In Part 2 of this series, we'll build a complete working chat application with it.

But the pattern itself is framework-agnostic. You could implement it with raw WebSockets in any language. The core insight is architectural: **design actions and state, not endpoints and DTOs.**

---

## The Mental Shift

The deepest change here isn't technical — it's how you think about the problem.

```
REST mental model:
  "What endpoint do I need?" → "Which HTTP method?" → "What's the response format?"
  → "Oh, I also need real-time..." → design another channel

Action/State mental model:
  "What does the user do?" → that's an Action
  "What changes as a result?" → that's State
  (real-time follows automatically)
```

REST is resource-oriented: you model *nouns* (users, messages, rooms) and apply *verbs* (GET, POST, DELETE) to them. The Action/State model is behavior-oriented: you model *what happens* and *what results from it*.

Neither is universally superior. But for interactive applications — the kind where multiple users affect shared state in real time — the Action/State model eliminates an entire layer of accidental complexity that REST forces you to manage.

---

## What's Next

In **Part 2**, we'll build a real-time chat application from scratch using this pattern. No REST endpoints. No separate WebSocket channel for real-time. Just actions, state, and a middleware pipeline on each side. We'll compare the resulting code side-by-side with a traditional REST implementation.

In **Part 3**, we'll push the pattern to its limits: multiple rooms, player-specific state views, tick-based batching for game servers, and horizontal scaling across server instances.

---

*If this idea resonates — or if you think it's completely wrong — I'd love to hear from you. The best patterns emerge from honest debate.*

*[Follow me on Medium](#) for Parts 2 and 3.*
