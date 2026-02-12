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
  - `develop` 타겟: 제한 없음
- 사용자에게 타겟 브랜치를 확인받으세요

### 1-4. 변경 사항 파악

```bash
git status
git diff
git log --oneline <base-branch>..HEAD
```

### 1-5. PR 생성

```bash
gh pr create --base <target> --title "<title>" --body "$(cat <<'EOF'
## Summary
<변경 내용 요약>

## Test plan
<테스트 계획>

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Phase 2: 코드리뷰 & CI 대응 (반복)

PR 생성 후 아래 루프를 코드리뷰와 CI가 모두 완료될 때까지 반복합니다.

### 2-1. CI & 리뷰 상태 확인

```bash
gh pr checks <pr-number>
gh api repos/chibimoons/flowdux/pulls/<pr-number>/comments | jq '.[] | {id, path, body, line}'
```

### 2-2. 코드리뷰 대응

리뷰 코멘트가 달렸으면:

1. **코멘트 내용 분석** — 수정이 필요한지 판단
2. **수정이 필요한 경우:**
   - 코드 수정
   - 테스트 실행하여 통과 확인
   - 커밋 & 푸시
   - 해당 코멘트에 답글: 어떻게 수정했는지 명시 (커밋 해시 포함)
3. **수정이 불필요한 경우:**
   - 해당 코멘트에 답글: 수정하지 않는 이유 설명

```bash
# 리뷰 코멘트에 답글
gh api repos/chibimoons/flowdux/pulls/<pr-number>/comments/<comment-id>/replies \
  -f body="Fixed in <commit-hash>. <설명>"
```

**중요: 모든 리뷰 코멘트에 반드시 답글을 남기세요. 수정 여부와 관계없이.**

### 2-3. CI 실패 대응

CI가 실패했으면:

```bash
gh run view <run-id> --log-failed
```

실패 원인 분석 → 수정 → 커밋 & 푸시

### 2-4. 상태 재확인

```bash
gh pr checks <pr-number>
gh api repos/chibimoons/flowdux/pulls/<pr-number>/comments | jq '
  ( [.[].in_reply_to_id] | unique ) as $replied_ids
  | [ .[] | select(.in_reply_to_id == null and (.id | IN($replied_ids[]) | not)) ]
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
