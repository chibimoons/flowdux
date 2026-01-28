# Claude Code Project Context

## Git/GitHub Multi-Account Setup

This environment uses multiple GitHub accounts. **Before any git push operation**, always verify the active account:

```bash
gh auth status
```

If the wrong account is active, switch to the repository owner's account:

```bash
gh auth switch -u <owner-account>
```

Check `gh auth status` output to identify available accounts and ensure the correct one is active before pushing.

## Project Structure

- `kotlin/` - Kotlin Multiplatform modules
  - `flowdux/` - Core state management library
  - `flowdux-remote-core/` - Shared remote types (SharedAction, codecs)
  - `flowdux-remote-client/` - Client middleware for remote state
  - `flowdux-remote-server/` - Server middleware for remote state
  - `flowdux-remote-ktor/` - Ktor WebSocket implementation
  - `flowdux-timetravel/` - Time travel debugging
  - `sample-*` - Sample applications

- `dart/` - Dart/Flutter implementation
  - `flowdux/` - Core library (published to pub.dev)

## Release Process

### Kotlin (JitPack)
- Git Flow: develop → release/x.x.x → main → tag
- Tag format: `1.x.x` (no prefix, for JitPack compatibility)

### Dart (pub.dev)
- Tag format: `dart/x.x.x`
- Publish: `dart pub publish` from `dart/flowdux/`

## Key Concepts

- `FlowHolderAction` - Action that holds a Flow/Stream of inner actions
- `FlowActionDelivery` - `Emit` (default, bypasses middleware) or `Dispatch` (full pipeline)
- `SharedAction` - Marker interface for actions shared between client and server
  - `ServerSharedAction` - Client → server (intercepted by `ClientRemoteMiddleware`)
  - `ClientSharedAction` - Server → client (intercepted by `ServerRemoteMiddleware`)
- `RemoteConnection` - Client-side transport abstraction (WebSocket, SSE, etc.)
- `ServerConnection` - Server-side transport abstraction (incoming Flow + send)
