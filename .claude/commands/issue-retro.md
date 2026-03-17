---
description: "이슈 작업 회고 — 잘된 점/아쉬운 점 분석 → rules·mistakes 갱신 → 워크플로우 개선"
argument-hint: <이슈번호>
allowed-tools: [Bash, Read, Edit, Write, Glob, Grep, Agent]
---

# 이슈 작업 회고

이슈 번호: `$ARGUMENTS`

> 작업 완료 후 머지 전에 실행합니다. 프로세스와 도구를 개선하는 것이 목적입니다.

---

## Phase 1: 사전 검증

### 1-1. 인자 확인

`$ARGUMENTS`가 비어있거나 양의 정수가 아니면 사용자에게 올바른 이슈 번호를 요청하고 중단하세요.

### 1-2. 문서 존재 확인

아래 파일이 존재하는지 확인:

- `docs/issue/$ARGUMENTS/plan.md` (필수)
- `docs/issue/$ARGUMENTS/context.md` (필수)
- `docs/issue/$ARGUMENTS/review-log.md` (있으면 사용)
- `docs/issue/$ARGUMENTS/result.md` (있으면 사용)

필수 파일이 없으면 → "이슈 #$ARGUMENTS 문서가 없습니다." 출력 후 종료

---

## Phase 2: 자료 수집

### 2-1. 이슈 문서 읽기

`docs/issue/$ARGUMENTS/` 디렉토리의 모든 `.md` 파일을 Read합니다.

### 2-2. 작업 이력 수집

```bash
git log --oneline develop..HEAD
git diff develop...HEAD --stat
```

### 2-3. 기존 rules·mistakes 읽기

```bash
# 현재 rules 목록
ls .claude/rules/
```

- `.claude/mistakes.md` Read
- `.claude/rules/*.md` 목록 확인

---

## Phase 3: 회고 분석

Agent(general-purpose) 서브에이전트에 아래 프롬프트와 자료를 전달합니다:

```
당신은 소프트웨어 개발 프로세스 개선 전문가입니다.
아래 자료를 기반으로 이슈 작업을 회고하세요.

## 분석 기준

### 1. 잘된 점
- 코드 품질이 사전에 개선된 사례 (리뷰에서 발견 → 수정)
- 효율적이었던 워크플로우 패턴
- 좋은 설계 결정

### 2. 아쉬운 점
- 반복된 수정이 필요했던 항목 (같은 파일을 여러 번 고친 경우)
- 리뷰에서 지적받은 항목 중 사전에 방지 가능했던 것
- 문서와 코드의 불일치가 발생한 시점과 원인

### 3. 반복된 실수
- mistakes.md에 이미 있는 실수가 반복되었는지 확인
- 새로운 실수 패턴 식별

### 4. 개선 제안
각 제안을 아래 카테고리로 분류:

- **rule 추가**: `.claude/rules/`에 새 규칙 파일로 방지 가능한 항목
  - 기존 rules와 중복되지 않는지 확인
- **mistake 추가**: `.claude/mistakes.md`에 기록할 새 실수 패턴
  - 기존 mistakes와 중복되지 않는지 확인
- **워크플로우 수정**: `.claude/commands/*.md` 스킬 개선 사항
- **해당 없음**: 일회성 이슈로 시스템 변경 불필요

## 출력 포맷

각 카테고리별로 구체적인 변경 내용을 제시하세요.
rule 추가 시 파일명과 내용을, mistake 추가 시 상황/원인/규칙을 명시하세요.
```

**Agent에 전달할 자료:**
- `docs/issue/$ARGUMENTS/` 내 모든 문서
- `git log` 결과 (커밋 히스토리)
- `git diff --stat` 결과 (변경 파일 요약)
- `.claude/mistakes.md` (기존 실수 목록)
- `.claude/rules/` 파일 목록 (기존 규칙)

---

## Phase 4: 사용자 확인 & 적용

### 4-1. 분석 결과 출력

회고 결과를 아래 형태로 사용자에게 출력합니다:

```
## 이슈 #$ARGUMENTS 회고

### 잘된 점
- ...

### 아쉬운 점
- ...

### 반복된 실수
- ...

### 개선 제안

| # | 카테고리 | 내용 | 적용 대상 |
|---|----------|------|----------|
| 1 | rule 추가 | ... | `.claude/rules/{name}.md` |
| 2 | mistake 추가 | ... | `.claude/mistakes.md` |
| 3 | 워크플로우 수정 | ... | `.claude/commands/{name}.md` |
```

### 4-2. 사용자 승인

개선 제안 각 항목에 대해 사용자에게 적용 여부를 확인합니다.

> **자율 모드 호출 시**: `issue-auto`에서 호출된 경우 사용자 승인 단계를 건너뛰고 모든 항목을 자동 승인합니다. `issue-auto.md`의 Phase 7에 명시된 오버라이드 규칙을 따릅니다.

### 4-3. 승인된 항목 적용

- **rule 추가**: `.claude/rules/` 에 파일 생성
- **mistake 추가**: `.claude/mistakes.md`에 항목 추가
- **워크플로우 수정**: 해당 `.claude/commands/*.md` 수정

### 4-4. 커밋

변경 사항을 커밋합니다:

```bash
git add .claude/rules/ .claude/mistakes.md .claude/commands/ docs/issue/$ARGUMENTS/
git commit -m "$(cat <<'EOF'
chore: apply retrospective improvements from #$ARGUMENTS

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Phase 5: 완료 보고

```
이슈 #$ARGUMENTS 회고 완료:
- rule 추가: {N}건
- mistake 추가: {M}건
- 워크플로우 수정: {K}건
```
