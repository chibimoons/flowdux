# PR 리뷰 & CI 대응

기존 PR의 코드리뷰와 CI 결과에 대응합니다. 인자로 PR 번호를 받습니다: `/pr-respond 123`

---

## 1. PR 상태 확인

```bash
gh pr view <pr-number> --repo chibimoons/flowdux --json title,state,headRefName,baseRefName
gh pr checks <pr-number> --repo chibimoons/flowdux
```

## 2. 리뷰 코멘트 확인

```bash
gh api repos/chibimoons/flowdux/pulls/<pr-number>/comments --jq '.[] | {id, path, body, line, in_reply_to_id}'
```

미답변 코멘트만 필터:

```bash
gh api repos/chibimoons/flowdux/pulls/<pr-number>/comments --jq '
  ( [.[].in_reply_to_id] | unique ) as $replied_ids
  | [ .[] | select(.in_reply_to_id == null and (.id as $id | $replied_ids | index($id) | not)) ]
'
```

## 3. 코멘트 대응 (각 코멘트별)

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

## 4. CI 실패 대응

CI가 실패했으면:

```bash
# PR의 CI 상태 요약 확인
gh pr checks <pr-number> --repo chibimoons/flowdux

# 관련 GitHub Actions run 목록 확인 (run-id 확인용)
gh run list --repo chibimoons/flowdux --limit 10

# 실패한 run의 로그 터미널에서 바로 확인
gh run view <run-id> --repo chibimoons/flowdux --log-failed
```

실패 원인 분석 → 수정 → 테스트 확인 → 커밋 & 푸시

## 5. Copilot 코드리뷰 재요청 & 대기

3~4단계에서 코드를 푸시했으면 Copilot에게 코드리뷰를 재요청하고 **리뷰가 도착할 때까지 대기**합니다.
코드 수정/푸시가 없었으면 이 단계를 건너뜁니다.

### 5-1. 재요청

> **주의**: `gh pr edit --add-reviewer copilot`은 이미 등록된 리뷰어에게 재트리거되지 않습니다. Copilot 재리뷰를 트리거할 때에는 `requested_reviewers` API를 사용하세요. Copilot은 GitHub App 봇이라 응답이나 `requested_reviewers` 목록에 나타나지 않으므로, 리뷰 도착 확인은 아래 5-3 단계처럼 **reviews API 폴링**으로 해야 합니다.

```bash
gh api repos/chibimoons/flowdux/pulls/<pr-number>/requested_reviewers \
  -X POST -f 'reviewers[]=copilot-pull-request-reviewer[bot]'
```

### 5-2. 최신 커밋 SHA 확인

```bash
LATEST_COMMIT=$(gh pr view <pr-number> --repo chibimoons/flowdux --json headRefOid --jq '.headRefOid')
```

### 5-3. Copilot 리뷰 도착 대기

최신 커밋(`$LATEST_COMMIT`)에 대한 Copilot 리뷰가 제출될 때까지 10초 간격으로 폴링합니다 (최대 10분).

```bash
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

> Copilot은 GitHub App 봇(`copilot-pull-request-reviewer[bot]`)이라 `requested_reviewers`에 나타나지 않습니다. reviews API로 확인해야 합니다.

## 6. 상태 재확인

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

→ 두 조건 모두 충족될 때까지 2번부터 반복

## 7. 완료

```
PR #<number> 리뷰 대응 완료:
- CI: 모두 통과
- 코드리뷰: 모든 코멘트 대응 완료
- URL: <pr-url>
```
