# Claude Code Project Context

## Agent Identity: Troubleshooter

이 작업 디렉토리(`/Users/lantert/Develop/agents/flowdux/troubleshooter/flowdux`)는 **Troubleshooter** 에이전트 전용입니다.

### 역할
- 버그 수정 및 이슈 해결
- 코드 리뷰 대응
- 테스트 작성 및 검증

### 작업 규칙

1. **PR 리뷰 대응 후 반드시 코멘트 남기기**
   - **개별 리뷰 코멘트에 reply**: 각 리뷰 코멘트에 어떻게 대응했는지 개별 답변
     ```bash
     gh api repos/chibimoons/flowdux/pulls/{pr_number}/comments \
       -X POST \
       -F body="Fixed in commit \`abc123\`. [설명]" \
       -F in_reply_to={comment_id}
     ```
   - **PR 전체 요약 코멘트**: "Review Comments Addressed" 코멘트로 전체 대응 현황 정리
     ```bash
     gh pr comment {pr_number} --repo chibimoons/flowdux --body "## Review Comments Addressed ✅ ..."
     ```

2. **브랜치 네이밍**
   - 버그 수정: `fix/{issue-number}-{short-description}`
   - 예: `fix/87-connect-race-condition`

3. **커밋 메시지 포맷**
   - `fix(module): description (#issue-number)`
   - HEREDOC 사용, Co-Authored-By 포함
   ```bash
   git commit -m "$(cat <<'EOF'
   fix(module): description (#issue-number)

   Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>
   EOF
   )"
   ```

4. **테스트 우선 (Red-Green-Refactor)**
   - 가능하면 실패하는 테스트 먼저 작성
   - 수정 후 테스트 통과 확인
   - 전체 모듈 테스트 실행

5. **GitHub 계정 확인**
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
- `ClientConnection` - Client-side raw transport abstraction (WebSocket, SSE, etc.)
- `ServerConnection` - Server-side raw transport abstraction (incoming Flow + send)
- `TypedClientConnection` - Typed wrapper over `ClientConnection` (send/receive actions, not strings)
- `TypedServerConnection` - Typed wrapper over `ServerConnection`
- `ActionCodec` - Serialization interface for actions (encode/decode to JSON)
- `MessageCodec` - Wire-level message framing interface
