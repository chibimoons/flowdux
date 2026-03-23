---
description: "이슈 작업 초기화 — 폴더 생성 + 작업계획서 + 컨텍스트 문서 작성"
argument-hint: <이슈번호>
allowed-tools: [Bash, Read, Write, Glob, Grep, Agent]
---

# 이슈 작업 초기화

이슈 번호: `$ARGUMENTS`

---

## Phase 1: 사전 검증

### 1-1. 인자 확인

`$ARGUMENTS`가 비어있거나 양의 정수가 아니면 사용자에게 올바른 이슈 번호를 요청하고 중단하세요.

### 1-2. GitHub 계정 확인

```bash
gh auth status
```

`chibimoons` 계정이 Active가 아니면:

```bash
gh auth switch -u chibimoons
```

### 1-3. 이슈 정보 수집

```bash
gh issue view $ARGUMENTS --repo chibimoons/flowdux --json number,title,body,labels,comments,state
```

- **CLOSED** → "이슈 #$ARGUMENTS는 이미 닫혀 있습니다." 출력 후 종료
- **OPEN** → Phase 2로 진행

### 1-4. 기존 문서 확인

`docs/issue/$ARGUMENTS/` 디렉토리가 이미 존재하면:

- `plan.md`, `context.md`가 모두 있으면 → "이슈 #$ARGUMENTS 문서가 이미 존재합니다. 기존 문서를 업데이트하세요." 출력 후 종료
- `plan.md`만 있거나 `context.md`만 있는 부분 생성 상태이면 → 기존 파일은 덮어쓰지 말고, 누락된 파일만 새로 생성하며 Phase 2로 진행
- 디렉토리만 있고 파일이 없으면 → Phase 2로 진행

---

## Phase 2: 코드베이스 분석

### 2-1. 관련 코드 탐색

Agent(Explore) 서브에이전트로 이슈에서 요구하는 수정 범위를 파악:

- 어떤 파일을 수정해야 하는지
- 기존 코드 구조와 패턴
- 영향 범위 분석
- 기존 관련 문서 (`docs/dev/`, `docs/design/`, `docs/guide/` 등) 확인

### 2-2. 이슈 유형 판단

라벨과 제목/본문으로 판단:

| 유형 | 브랜치 접두사 | 커밋 접두사 |
|------|-------------|------------|
| 기능 추가 | `feature/` | `feat` |
| 버그 수정 | `fix/` | `fix` |
| 테스트 | `feature/` | `test` |
| 문서 | `docs/` | `docs` |
| 리팩터링 | `feature/` | `refactor` |
| 성능 개선 | `feature/` | `perf` |
| 보안 수정 | `fix/` | `security` |
| 기타 | `feature/` | `chore` |

> **참고**: `test`, `refactor`, `perf`, `chore` 작업은 `feature/` 브랜치를 사용합니다.
> 문서 작업 브랜치는 이슈 번호를 포함하지 않고 `docs/{description}` 형태를 사용합니다.

---

## Phase 3: 문서 작성

### 3-1. 폴더 생성

```bash
mkdir -p docs/issue/$ARGUMENTS
```

### 3-2. plan.md 작성

아래 템플릿을 기반으로 이슈 분석 결과를 반영하여 작성:

```markdown
# 작업계획서 — 이슈 #{번호}: {제목}

## 개요

{이슈 요약 — 무엇을 왜 하는지}

## 브랜치 정보

- 브랜치 (일반): `{prefix}/{issue-number}-{description}`
- 브랜치 (문서 docs): `docs/{description}` (이슈 번호 없음)
- 타겟: `develop`
- 유형: `{feat/fix/test/refactor/perf/security/docs/chore}`
- 스코프: `{모듈명}`

## 수정 대상

| # | 파일 | 변경 내용 | 이유 |
|---|------|----------|------|
| 1 | ... | ... | ... |

## 단계별 계획

### Step 1: {제목}

- [ ] {작업 항목}
- [ ] {작업 항목}

### Step 2: {제목}

- [ ] {작업 항목}

## 검증 방법

- {테스트 실행 방법}
- {수동 확인 항목}

## 진행 상태

| Step | 상태 | 비고 |
|------|------|------|
| Step 1 | ⏳ 대기 | |
| Step 2 | ⏳ 대기 | |
```

**작성 규칙:**
- Step은 독립적으로 검증 가능한 단위로 분리
- 각 Step에 구체적인 파일 변경 내역 포함
- 검증 방법 반드시 명시

### 3-3. context.md 작성

아래 템플릿을 기반으로 작성:

```markdown
# 컨텍스트 — 이슈 #{번호}: {제목}

## 배경

{이슈가 필요한 이유, 현재 문제점}

## 관련 자료

- GitHub 이슈: #{번호}
- 관련 문서: {있으면 링크}
- 관련 코드: {주요 파일 경로}

## 논의 사항

### {날짜}: {주제}

{논의 내용과 결론}

## 결정 사항

| # | 결정 | 이유 | 날짜 |
|---|------|------|------|
| 1 | ... | ... | ... |

## 제약 조건

- {기술적 제약}
- {비즈니스 제약}
```

**작성 규칙:**
- 이슈 본문/코멘트에서 논의된 내용을 정리
- 이전 대화에서 논의/결정된 사항이 있으면 반드시 포함
- 기술적 배경과 설계 결정의 근거를 명시

---

## Phase 4: 완료 보고

```
이슈 #$ARGUMENTS 작업 초기화 완료:
- 폴더: docs/issue/$ARGUMENTS/
- 작업계획서: docs/issue/$ARGUMENTS/plan.md
- 컨텍스트: docs/issue/$ARGUMENTS/context.md
```
