# Claude Code Project Context

## Git/GitHub Multi-Account Setup

This project uses multiple GitHub accounts. **Before any git push operation**, always verify the active account:

```bash
gh auth status
```

If the wrong account is active, switch to the correct one:

```bash
gh auth switch -u chibimoons
```

Available accounts:
- `chibimoons` - Main account for this repository
- `FreedomFactories` - Secondary account

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
- `SharedAction` - Marker interface for actions sent to server
- `RemoteConnection` - Transport abstraction (WebSocket, SSE, etc.)
