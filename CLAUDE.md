# Claude Code Project Context

## Agent Identity: 덕스 (Dux)

이 작업 디렉토리(`/Users/lantert/Develop/agents/flowdux/dux/flowdux`)는 **덕스** 에이전트 전용입니다.

### 역할
- 문서화 및 샘플 앱 개발
- 기능 구현 및 개선
- 코드 리뷰 대응

### 작업 규칙

1. **PR 리뷰 대응 후 반드시 PR에 코멘트 남기기**
   - 리뷰 코멘트 수정 완료 시 해당 코멘트에 답글 작성
   - 어떤 코멘트를 어떻게 대응했는지 명시

2. **브랜치 네이밍**
   - 기능: `feature/{issue-number}-{short-description}`
   - 문서: `docs/{short-description}`
   - 릴리즈: `release/{version}`
   - 핫픽스: `hotfix/{short-description}`

3. **커밋 메시지 포맷**
   - HEREDOC 사용, Co-Authored-By 포함
   ```bash
   git commit -m "$(cat <<'EOF'
   feat(module): description

   Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>
   EOF
   )"
   ```

4. **GitHub 계정 확인**
   - push 전 항상 `gh auth status` 확인
   - `chibimoons` 계정 사용

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
  - `flowdux-remote-core/` - Shared action markers (SharedAction, ServerSharedAction, ClientSharedAction)
  - `flowdux-remote-client/` - Client middleware and typed connection interface
  - `flowdux-remote-server/` - Server middleware and typed connection interface
  - `flowdux-remote-serialization/` - Codec interfaces, kotlinx.serialization bindings, typed connection bridge
  - `flowdux-remote-ktor/` - Ktor WebSocket implementation
  - `flowdux-timetravel/` - Time travel debugging
  - `samples/flowdux/` - Core sample apps (jvm, android, web, wasm, kmm)
  - `samples/flowdux-remote/` - Remote sample apps (shared, simple, multi-client)

- `dart/` - Dart/Flutter implementation
  - `flowdux/` - Core library (published to pub.dev)

## Branch Strategy

- **기본 타겟 브랜치**: `develop`
- release 브랜치 외 모든 작업(feature, fix, docs 등)은 `develop`으로 PR 생성
- release 브랜치만 `main`으로 머지

```
feature/xxx  ─┐
fix/xxx      ─┼─► develop ─► release/x.x.x ─► main ─► tag
docs/xxx     ─┘
```

## Release Process

### Kotlin (Maven Central — primary)
- Git Flow: develop → release/x.x.x → main → tag
- Tag format: `1.x.x` (no prefix)
- groupId: `io.github.chibimoons`
- Automated via GitHub Actions (`publish-maven-central.yml`): tag push triggers publish
- Manual dry-run: workflow_dispatch with `dry_run: true`
- Plugin: vanniktech/gradle-maven-publish-plugin 0.36.0
- Version: managed in `gradle.properties` (`flowdux.version`)
- See `docs/design/MAVEN_CENTRAL_PUBLISH.md` for full guide

### Kotlin (JitPack — legacy)
- Still supported for existing consumers
- groupId: `com.github.chibimoons`
- `jitpack.yml` builds with `JITPACK=true` (JVM-only artifacts)

### Dart (pub.dev)
- Tag format: `dart/x.x.x`
- Publish: `dart pub publish` from `dart/flowdux/`

## Key Concepts

- `FlowHolderAction` - Action that holds a Flow/Stream of inner actions
- `FlowActionDelivery` - `Emit` (default, bypasses middleware) or `Dispatch` (full pipeline)
- `SharedAction` - Marker interface for actions shared between client and server
  - `ServerSharedAction` - Client → server (intercepted by `ClientRemoteMiddleware`)
  - `ClientSharedAction` - Server → client (intercepted by `ServerRemoteMiddleware`)
- `ClientConnection` - Client-side raw transport abstraction (WebSocket, SSE, etc.)
- `ServerConnection` - Server-side raw transport abstraction (incoming Flow + send)
- `TypedClientConnection` - Typed wrapper over `ClientConnection` (send/receive actions, not strings)
- `TypedServerConnection` - Typed wrapper over `ServerConnection`
- `ActionCodec` - Serialization interface for actions (encode/decode to JSON)
- `MessageCodec` - Wire-level message framing interface
