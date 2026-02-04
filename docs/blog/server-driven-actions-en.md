# What If the Server Could Dispatch Actions to the Client?

Imagine building a real-time chat app. How do you tell the client "a new message arrived"? Poll a REST API? Send JSON over WebSocket? And how does the client update its state with that data?

This post explores existing approaches and introduces a slightly different way.

## Existing Approaches

There are already various patterns for servers to control clients.

**Server-Driven UI (SDUI)** — Used by Airbnb, Shopify, etc. The server sends UI structure (component tree, layout) as JSON, and the client renders it. Powerful for updating UI without app releases, but the client becomes a simple renderer.

**HTML-over-the-Wire** — Phoenix LiveView, Laravel Livewire, HTMX, etc. The server sends HTML fragments directly to update the DOM. Greatly reduces frontend complexity, but requires constant server connection.

**Server-Side Redux** — A Redux Store lives on the server, and state changes are synced to clients. Guarantees state consistency, but limits client-side logic.

The common thread: servers send **"results"** (UI structure, HTML, State). The client simply reflects what it receives.

## A Different Approach: Send Actions

What if the server sent **"commands"** instead of results? Instead of **"render this"**, the server sends **"dispatch this Action"**.

```
┌────────────┐                    ┌────────────┐
│   Server   │  ── Action ──▶    │   Client   │
│   Store    │  ◀── Action ──    │   Store    │
└────────────┘                    └────────────┘
```

Both sides have independent Stores and exchange Actions.

```kotlin
// Actions defined in shared module
sealed interface ChatAction {
    // Client → Server (implementing ServerSharedAction sends it to server)
    data class SendMessage(val text: String) : ChatAction, ServerSharedAction

    // Server → Client (implementing ClientSharedAction sends it to client)
    data class SyncState(val messages: List<Message>) : ChatAction, ClientSharedAction
    data class UserKicked(val reason: String) : ChatAction, ClientSharedAction
}
```

When the server calls `store.dispatch(UserKicked("spam"))`, this Action is sent to the client via WebSocket, and the client's Reducer handles it.

## Key Differences

| | Traditional | Action-Driven |
|---|------------|---------------|
| **Sends** | Results (UI/State) | Commands (Action) |
| **Client** | Renderer | Has its own Store |
| **Offline** | Doesn't work | Local Actions work |
| **Types** | Separate schema | Compile-time verified |

The key insight: the client is **not a "dumb renderer" but an independent application with its own logic**.

Even when disconnected, local Actions (UI toggles, input handling, etc.) continue to work, syncing with the server upon reconnection.

## When Is This Useful?

- When clients need complex local logic (e.g., offline editing, optimistic updates)
- When offline support matters
- When type safety between server and client is important
- When sharing logic across platforms (Android, iOS, Web) with Kotlin Multiplatform

## When to Use Something Else

- **Simple CRUD apps** → REST API + client state management is enough
- **Server-centric apps, SEO matters** → LiveView, HTMX are simpler
- **Rapid prototyping** → Livewire, LiveView offer higher productivity

There's no silver bullet. Choose the right tool for your requirements.

## References

- [Airbnb Server-Driven UI](https://www.infoq.com/news/2021/07/airbnb-server-driven-ui/)
- [Phoenix LiveView](https://github.com/phoenixframework/phoenix_live_view)
- [HTMX - Hypermedia-Driven Applications](https://htmx.org/essays/hypermedia-driven-applications/)
- [flowdux-remote samples](https://github.com/chibimoons/flowdux/tree/main/kotlin/samples/flowdux-remote)
