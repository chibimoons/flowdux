# PR 리뷰 & CI 대응

기존 PR의 코드리뷰와 CI 결과에 대응합니다. 인자로 PR 번호를 받습니다: `/pr-respond 123`

---

## 1. PR 상태 확인

```bash
gh pr view <pr-number> --json title,state,headRefName,baseRefName
gh pr checks <pr-number>
```

## 2. 리뷰 코멘트 확인

```bash
gh api repos/chibimoons/flowdux/pulls/<pr-number>/comments | jq '.[] | {id, path, body, line, in_reply_to_id}'
```

미답변 코멘트만 필터:

```bash
gh api repos/chibimoons/flowdux/pulls/<pr-number>/comments | jq '
  ( [.[].in_reply_to_id] | unique ) as $replied_ids
  | [ .[] | select(.in_reply_to_id == null and (.id | IN($replied_ids[]) | not)) ]
'
```

## 3. 코멘트 대응 (각 코멘트별)

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

## 4. CI 실패 대응

CI가 실패했으면:

```bash
gh run view <run-id> --log-failed
```

실패 원인 분석 → 수정 → 테스트 확인 → 커밋 & 푸시

## 5. 상태 재확인

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

→ 두 조건 모두 충족될 때까지 2번부터 반복

## 6. 완료

```
PR #<number> 리뷰 대응 완료:
- CI: 모두 통과
- 코드리뷰: 모든 코멘트 대응 완료
- URL: <pr-url>
```
