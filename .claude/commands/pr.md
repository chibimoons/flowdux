# PR 워크플로우

PR 생성부터 머지까지 전체 라이프사이클을 수행합니다.

---

## Phase 1: PR 생성

### 1-1. Git Workflow 규칙 로드

`docs/dev/git-workflow.md`를 Read하여 규칙을 확인하세요.

### 1-2. GitHub 계정 확인

```bash
gh auth status
```

`chibimoons` 계정이 Active가 아니면:

```bash
gh auth switch -u chibimoons
```

### 1-3. 브랜치 & 타겟 확인

- 현재 브랜치명 확인
- 타겟 브랜치 결정:
  - `main` 타겟: 브랜치명이 `release/*`, `hotfix/*`, `docs/*` 인지 확인 (아니면 CI fail)
  - `develop` 타겟: feature, fix, chore, refactor, test 등
- 사용자에게 타겟 브랜치를 확인받으세요

### 1-4. 변경 사항 파악

```bash
git status
git diff
git log --oneline <base-branch>..HEAD
```

### 1-5. 로컬 코드 리뷰

PR 생성 전에 전체 변경사항을 리뷰합니다. Task(general-purpose) 서브에이전트에 아래 프롬프트를 전달하세요:

```
코드 리뷰를 수행하세요. 수정은 하지 마세요.

대상: `git diff --name-only <base-branch>...HEAD`로 변경 파일 목록을 확인하고, 각 파일을 Read로 읽어서 변경 부분 중심으로 리뷰

리뷰 관점:
1. 버그/논리 오류 — 의도와 다르게 동작할 수 있는 코드
2. 보안 취약점 — injection, 인증 우회, 민감 정보 노출
3. 프로젝트 컨벤션 위반 — CLAUDE.md의 규칙과 기존 코드 패턴 참조
4. 불필요한 코드 — 미사용 import, 데드코드, 불필요한 주석
5. 누락된 에러 처리 — 예외 미처리, 경계값 미검증

결과를 severity별로 정리하세요:
- [CRITICAL] 반드시 수정 필요
- [WARNING] 수정 권장
- [INFO] 참고 사항

문제가 없으면 "No issues found"로 보고하세요.
```

**리뷰 결과 처리:**
- CRITICAL 발견 시 → 수정 → 커밋 후 다시 리뷰 (최대 2회 반복, 이후 사용자 확인)
- WARNING만 남은 경우 → 사용자에게 진행 여부 확인
- 문제 없으면 → 1-6으로 진행

### 1-6. PR 생성

```bash
gh pr create --repo chibimoons/flowdux --base <target> --title "<title>" --body "$(cat <<'EOF'
## Summary
<변경 내용 요약>

## Test plan
<테스트 계획>

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

### 1-7. Copilot 리뷰 요청 & 대기

> **주의**: `gh pr edit --add-reviewer copilot`은 재요청 시 트리거되지 않습니다. 초기/재요청 모두 `requested_reviewers` API를 사용하세요. Copilot은 GitHub App 봇이라 `requested_reviewers` 목록에 나타나지 않으므로, 리뷰 도착 확인은 **reviews API 폴링**으로 해야 합니다.

```bash
gh api repos/chibimoons/flowdux/pulls/<pr-number>/requested_reviewers \
  -X POST -f 'reviewers[]=copilot-pull-request-reviewer[bot]'
```

최신 커밋에 대한 Copilot 리뷰가 도착할 때까지 10초 간격으로 폴링합니다 (최대 10분):

```bash
LATEST_COMMIT=$(gh pr view <pr-number> --repo chibimoons/flowdux --json headRefOid --jq '.headRefOid')
count=0
for i in $(seq 1 60); do
  count=$(gh api repos/chibimoons/flowdux/pulls/<pr-number>/reviews \
    --jq "[.[] | select(.user.login == \"copilot-pull-request-reviewer[bot]\" and .commit_id == \"$LATEST_COMMIT\")] | length")
  count=${count:-0}
  if [ "$count" -gt 0 ]; then
    echo "Copilot review received"
    break
  fi
  echo "Waiting for Copilot review... (${i}0s)"
  sleep 10
done
if [ "$count" -eq 0 ]; then
  echo "Timed out waiting for Copilot review (10min). Proceeding without it."
fi
```

---

## Phase 2: 코드리뷰 & CI 대응 (반복)

PR 생성 후 아래 루프를 코드리뷰와 CI가 모두 완료될 때까지 반복합니다.

### 2-1. CI & 리뷰 상태 확인

```bash
gh pr checks <pr-number> --repo chibimoons/flowdux
gh api repos/chibimoons/flowdux/pulls/<pr-number>/comments --jq '.[] | {id, path, body, line}'
```

### 2-2. 코드리뷰 대응

리뷰 코멘트가 달렸으면:

1. **코멘트 내용 분석** — 수정이 필요한지 판단
2. **수정이 필요한 경우:**
   - 코드 수정
   - 테스트 실행하여 통과 확인
   - 커밋 & 푸시
   - 해당 코멘트에 답글: 어떻게 수정했는지 명시
3. **수정이 불필요한 경우:**
   - 해당 코멘트에 답글: 수정하지 않는 이유 설명

```bash
# 리뷰 코멘트에 답글
gh api repos/chibimoons/flowdux/pulls/<pr-number>/comments/<comment-id>/replies \
  -X POST -f body="Fixed. <설명>"
```

**중요: 모든 리뷰 코멘트에 반드시 답글을 남기세요. 수정 여부와 관계없이.**

### 2-3. CI 실패 대응

CI가 실패했으면:

```bash
# PR의 CI 상태 요약 확인
gh pr checks <pr-number> --repo chibimoons/flowdux

# 관련 GitHub Actions run 목록 확인 (run-id 확인용)
gh run list --repo chibimoons/flowdux --limit 10

# 실패한 run의 로그 터미널에서 바로 확인
gh run view <run-id> --repo chibimoons/flowdux --log-failed
```

실패 원인 분석 → 수정 → 커밋 & 푸시

### 2-4. Copilot 코드리뷰 재요청 & 대기

2-2~2-3에서 코드를 푸시했으면 Copilot에게 코드리뷰를 재요청하고 **리뷰가 도착할 때까지 대기**합니다.
코드 수정/푸시가 없었으면 이 단계를 건너뜁니다.

> **주의**: 재요청 시 `gh pr edit --add-reviewer copilot`은 이미 등록된 리뷰어에게 재트리거되지 않습니다. Copilot 재리뷰를 트리거할 때에는 `requested_reviewers` API를 사용하세요. Copilot은 GitHub App 봇이라 응답이나 `requested_reviewers` 목록에 나타나지 않으므로, 리뷰 도착 확인은 아래 **reviews API 폴링**으로 해야 합니다.

```bash
gh api repos/chibimoons/flowdux/pulls/<pr-number>/requested_reviewers \
  -X POST -f 'reviewers[]=copilot-pull-request-reviewer[bot]'
```

최신 커밋에 대한 Copilot 리뷰가 도착할 때까지 10초 간격으로 폴링합니다 (최대 10분):

```bash
LATEST_COMMIT=$(gh pr view <pr-number> --repo chibimoons/flowdux --json headRefOid --jq '.headRefOid')
count=0
for i in $(seq 1 60); do
  count=$(gh api repos/chibimoons/flowdux/pulls/<pr-number>/reviews \
    --jq "[.[] | select(.user.login == \"copilot-pull-request-reviewer[bot]\" and .commit_id == \"$LATEST_COMMIT\")] | length")
  count=${count:-0}
  if [ "$count" -gt 0 ]; then
    echo "Copilot review received"
    break
  fi
  echo "Waiting for Copilot review... (${i}0s)"
  sleep 10
done
if [ "$count" -eq 0 ]; then
  echo "Timed out waiting for Copilot review (10min). Proceeding without it."
fi
```

### 2-5. 상태 재확인

```bash
gh pr checks <pr-number> --repo chibimoons/flowdux
gh api repos/chibimoons/flowdux/pulls/<pr-number>/comments --jq '
  ( [.[].in_reply_to_id] | unique ) as $replied_ids
  | [ .[] | select(.in_reply_to_id == null and (.id as $id | $replied_ids | index($id) | not)) ]
  | length
'
```

- CI 모두 통과 ✓
- 미답변 리뷰 코멘트 0개 ✓

→ 두 조건 모두 충족될 때까지 2-1부터 반복

---

## Phase 3: 완료

모든 체크가 통과되면 사용자에게 머지 준비 완료를 알립니다.

```
PR #<number> 머지 준비 완료:
- CI: 모두 통과
- 코드리뷰: 모든 코멘트 대응 완료
- URL: <pr-url>
```
