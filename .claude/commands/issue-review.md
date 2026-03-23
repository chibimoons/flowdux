---
description: "이슈 작업 완료 리뷰 — Agent 평가 → 수정/이슈 등록 → 결과문서 작성"
argument-hint: <이슈번호>
allowed-tools: [Bash, Read, Edit, Write, Glob, Grep, Agent]
---

# 이슈 작업 완료 리뷰

이슈 번호: `$ARGUMENTS`

---

## Phase 1: 사전 검증

### 1-1. 인자 확인

`$ARGUMENTS`가 비어있거나 양의 정수가 아니면 사용자에게 올바른 이슈 번호를 요청하고 중단하세요.

### 1-2. 문서 존재 확인

아래 파일이 모두 존재하는지 확인:

- `docs/issue/$ARGUMENTS/plan.md`
- `docs/issue/$ARGUMENTS/context.md`

없으면 → "이슈 #$ARGUMENTS 문서가 없습니다. `/issue-init $ARGUMENTS`를 먼저 실행하세요." 출력 후 종료

### 1-3. GitHub 계정 확인

```bash
gh auth status
```

`chibimoons` 계정이 Active가 아니면:

```bash
gh auth switch -u chibimoons
```

---

## Phase 2: 리뷰 자료 수집

### 2-1. 문서 읽기

아래 파일을 모두 Read:

1. `docs/issue/$ARGUMENTS/plan.md` — 작업계획서
2. `docs/issue/$ARGUMENTS/context.md` — 컨텍스트
3. GitHub 이슈 본문:

```bash
gh issue view $ARGUMENTS --repo chibimoons/flowdux --json number,title,body,labels
```

### 2-2. 코드 변경 내역 수집

현재 브랜치의 변경 사항을 수집:

```bash
git fetch origin develop
git diff origin/develop...HEAD --stat
git diff origin/develop...HEAD
```

---

## Phase 3: Agent 리뷰

### 3-1. 리뷰 Agent 실행

Agent(general-purpose) 서브에이전트에 아래 프롬프트를 전달하여 리뷰를 수행합니다:

```
당신은 코드 리뷰 전문가입니다. 아래 자료를 기반으로 작업 결과를 평가하세요.

## 평가 기준

1. **완성도** — plan.md의 모든 Step이 완료되었는가?
2. **정확성** — 이슈 요구사항이 모두 충족되었는가?
3. **코드 품질** — 프로젝트 컨벤션(CLAUDE.md)을 따르는가?
4. **테스트** — 검증 방법이 실행되었는가?
5. **문서 일관성** — context.md의 결정사항이 코드에 반영되었는가?

## 평가 결과 포맷

각 항목에 대해:
- ✅ 충족
- ⚠️ 부분 충족 (수정 필요 사항 명시)
- ❌ 미충족 (구체적 사유 명시)

## 분류

발견된 문제를 아래 기준으로 분류:
- **즉시 수정**: 현재 PR에서 바로 수정 가능한 항목
- **이슈 등록**: 별도 이슈로 분리하여 추후 처리할 항목
- **참고**: 개선 제안이지만 필수가 아닌 항목
```

**Agent에 전달할 자료:**
- plan.md 내용
- context.md 내용
- GitHub 이슈 본문
- `git diff origin/develop...HEAD` 결과
- CLAUDE.md (프로젝트 컨벤션)

### 3-2. 리뷰 결과 정리

Agent 결과를 아래 형태로 정리:

| # | 항목 | 평가 | 상세 |
|---|------|------|------|
| 1 | 완성도 | ✅/⚠️/❌ | ... |
| 2 | 정확성 | ✅/⚠️/❌ | ... |
| 3 | 코드 품질 | ✅/⚠️/❌ | ... |
| 4 | 테스트 | ✅/⚠️/❌ | ... |
| 5 | 문서 일관성 | ✅/⚠️/❌ | ... |

---

## Phase 4: 수정 및 이슈 등록

### 4-1. 즉시 수정

⚠️ 또는 ❌ 중 "즉시 수정" 분류 항목을 수정합니다:

- 코드 수정
- 테스트 실행하여 통과 확인
- plan.md 진행 상태 업데이트

### 4-2. 이슈 등록

"이슈 등록" 분류 항목을 GitHub 이슈로 등록합니다:

```bash
gh issue create --repo chibimoons/flowdux \
  --title "{제목}" \
  --body "{본문 — 원본 이슈 #$ARGUMENTS 참조 포함}"
```

등록된 이슈 번호를 기록합니다.

### 4-3. 리뷰 로그 갱신

`docs/issue/$ARGUMENTS/review-log.md`에 "작업 완료 리뷰" 섹션을 추가합니다 (파일이 없으면 새로 생성):

```markdown
## 작업 완료 리뷰 (issue-review)

- **리뷰어**: Agent (general-purpose)
- **시점**: PR 머지 전

| # | 항목 | 평가 | 상세 |
|---|------|------|------|
| 1 | 완성도 | ... | ... |
| ... | ... | ... | ... |

### 즉시 수정

| # | 문제 | 수정 내용 |
|---|------|----------|
| 1 | ... | ... |

### 이슈 등록

| # | 이슈 | 제목 |
|---|------|------|
| 1 | #{n} | ... |
```

---

## Phase 5: result.md 작성

`docs/issue/$ARGUMENTS/result.md`를 작성합니다:

```markdown
# 결과문서 — 이슈 #{번호}: {제목}

## 초기 평가

| # | 항목 | 평가 | 상세 |
|---|------|------|------|
| 1 | 완성도 | ... | ... |
| 2 | 정확성 | ... | ... |
| 3 | 코드 품질 | ... | ... |
| 4 | 테스트 | ... | ... |
| 5 | 문서 일관성 | ... | ... |

## 수정 내역

| # | 문제 | 수정 내용 |
|---|------|----------|
| 1 | ... | ... |

## 등록된 이슈

| # | 이슈 | 제목 | 사유 |
|---|------|------|------|
| 1 | #{n} | ... | ... |

## 참고 사항

- {개선 제안 등}

## 최종 요약

- **작업 범위**: {수정된 파일 수, 주요 변경}
- **결과**: {성공/부분 성공}
- **후속 작업**: {등록된 이슈 목록}
- **PR**: #{pr-number}
```

---

## Phase 6: 완료 보고

```
이슈 #$ARGUMENTS 리뷰 완료:
- 결과문서: docs/issue/$ARGUMENTS/result.md
- 즉시 수정: {N}건
- 이슈 등록: {M}건 ({이슈 번호 목록})
- 참고: {K}건
```
