# Claude Code Project Context

## 역할
- 문서화 및 샘플 앱 개발
- 기능 구현 및 개선
- 버그 수정 및 이슈 해결
- 코드 리뷰 대응
- 테스트 작성 및 검증

## 작업 규칙

1. **PR 리뷰 대응 후 반드시 PR에 코멘트 남기기**
   - 리뷰 코멘트 수정 완료 시 해당 코멘트에 답글 작성
   - 어떤 코멘트를 어떻게 대응했는지 명시

2. **테스트 우선 (Red-Green-Refactor)**
   - 가능하면 실패하는 테스트 먼저 작성
   - 수정 후 테스트 통과 확인
   - 전체 모듈 테스트 실행

3. **Git 작업 규칙**: `docs/dev/git-workflow.md` 참조
   - 브랜치 네이밍, 커밋 메시지, GitHub 계정 확인, PR 타겟 브랜치, 릴리즈/핫픽스 프로세스 등 모든 Git 규칙이 정의되어 있음
   - PR 생성, 릴리즈, push 전 반드시 Read할 것

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

## Release Publishing

- **Kotlin (Maven Central)**: `io.github.chibimoons`, tag push → GitHub Actions 자동 배포 (~25분). 상세: `docs/design/MAVEN_CENTRAL_PUBLISH.md`
- **Kotlin (JitPack — legacy)**: `com.github.chibimoons`, `jitpack.yml`로 JVM-only 빌드
- **Dart (pub.dev)**: tag `dart/x.x.x`, `dart pub publish` from `dart/flowdux/`

## Key Concepts

- `FlowHolderAction` - Action that holds a Flow/Stream of inner actions
- `FlowActionDelivery` - `Emit` (default, bypasses middleware) or `Dispatch` (full pipeline)
- `SharedAction` - Marker interface for actions shared between client and server
  - `ServerSharedAction` - Client → server (intercepted by `SyncMiddleware`)
  - `ClientSharedAction` - Server → client (intercepted by `SingleClientSyncMiddleware` or `MultiClientSyncMiddleware`)
- `ClientConnection` - Client-side raw transport abstraction (WebSocket, SSE, etc.)
- `ServerConnection` - Server-side raw transport abstraction (incoming Flow + send)
- `TypedClientConnection` - Typed wrapper over `ClientConnection` (send/receive actions, not strings)
- `TypedServerConnection` - Typed wrapper over `ServerConnection`
- `ActionCodec` - Serialization interface for actions (encode/decode to JSON)
- `MessageCodec` - Wire-level message framing interface
