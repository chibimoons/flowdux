---
description: "이슈 자율 처리 — 분석 → 코드 수정 → PR 생성까지 확인 없이 자율 진행 (머지 제외)"
argument-hint: <이슈번호>
allowed-tools: [Bash, Read, Edit, Write, Glob, Grep, Agent, Skill]
---

# 이슈 자율 처리 (Autonomous Mode)

이슈 번호: `$ARGUMENTS`

> **자율 모드**: 사용자 확인 없이 자율 진행합니다. **머지는 수행하지 않습니다.**

---

## Phase 1: 사전 검증

### 1-1. 인자 확인

`$ARGUMENTS`가 비어있거나 양의 정수가 아니면 사용자에게 올바른 이슈 번호를 요청하고 중단하세요.

### 1-2. Git Workflow 규칙 로드

`docs/dev/git-workflow.md`를 Read하여 규칙을 확인하세요.

### 1-3. GitHub 계정 확인

```bash
gh auth status
```

`chibimoons` 계정이 Active가 아니면:

```bash
gh auth switch -u chibimoons
```

### 1-4. 이슈 상태 확인

```bash
gh issue view $ARGUMENTS --repo chibimoons/flowdux --json state,title,body,labels,comments
```

- **CLOSED** → "이슈 #$ARGUMENTS는 이미 닫혀 있습니다." 출력 후 종료
- **OPEN** → Phase 2로 진행

---

## Phase 2: 이슈 문서화 초기화

`/issue-init $ARGUMENTS`를 Skill로 호출합니다.

```
Skill: issue-init
Args: $ARGUMENTS
```

`issue-init`가 문서가 이미 존재한다고 안내하며 종료하는 경우에도, **초기화가 이미 완료된 것으로 간주하고 Phase 3로 계속 진행**합니다.

완료 후 `docs/issue/$ARGUMENTS/plan.md`를 Read하여 작업 계획을 확인합니다.

---

## Phase 3: 이슈 분석 & 계획

### 3-1. 이슈 정보 수집

이슈 제목, 본문, 라벨, 코멘트를 읽고 아래를 판단:

- **이슈 유형**: 라벨과 제목/본문으로 판단

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

- **브랜치명**: `{prefix}/{issue-number}-{short-description}` (영문 kebab-case)
  - 단, **문서 작업(`docs/`)** 은 `docs/{short-description}` 형태로 이슈 번호 없이 생성
- **커밋 스코프**: 수정 대상 모듈명 사용
  - `flowdux`, `remote-core`, `remote-client`, `remote-server`, `remote-ktor`, `remote-serialization`, `remote-auth`, `remote-multiplexer`, `remote-node-mediator`, `timetravel`
  - 여러 모듈에 걸치면 `remote` 등 상위 스코프 사용
  - 샘플/문서만 수정 시 `sample`, `docs` 사용
- **타겟 브랜치**: `develop` (이슈 기반 작업은 모두 develop 타겟, main 직접 반영이 필요한 문서는 별도 `/pr` 워크플로우 사용)

### 3-2. 수정 계획 출력 (확인 없이 진행)

분석 결과를 아래 표 형태로 **출력만** 하고 바로 Phase 4로 진행합니다:

```
이슈 #{번호}: {제목}

브랜치: {prefix}/{issue-number}-{description} (문서 작업: docs/{short-description})
타겟: develop
유형: {feat/fix/test/refactor/perf/security/docs/chore}
스코프: {모듈명}

| # | 수정 파일 | 변경 내용 | 이유 |
|---|----------|----------|------|
| 1 | kotlin/remote/... | ... | ... |
| 2 | kotlin/flowdux/... | ... | ... |

→ 자율 모드: 바로 진행합니다.
```

---

## Phase 4: 브랜치 생성 & 코드 수정

### 4-1. develop 최신화 & 브랜치 생성

```bash
git fetch origin develop
git checkout -b {branch-name} origin/develop
```

### 4-2. 코드 수정

Phase 3에서 출력한 계획에 따라 코드를 수정합니다.

**각 Step 완료 시 `docs/issue/$ARGUMENTS/plan.md`의 진행 상태를 업데이트합니다:**
- `⏳ 대기` → `✅ 완료` (성공 시)
- `⏳ 대기` → `❌ 실패` (실패 시, 사유 기록)

### 4-3. 테스트 실행

수정한 모듈에 해당하는 테스트를 실행합니다:

```bash
# 모듈별 JVM 테스트
./gradlew :kotlin:flowdux:jvmTest
./gradlew :kotlin:flowdux-remote-core:jvmTest
./gradlew :kotlin:flowdux-remote-client:jvmTest
./gradlew :kotlin:flowdux-remote-server:jvmTest
./gradlew :kotlin:flowdux-remote-ktor:jvmTest
./gradlew :kotlin:flowdux-remote-serialization:jvmTest
./gradlew :kotlin:flowdux-remote-auth:jvmTest
./gradlew :kotlin:flowdux-remote-multiplexer:jvmTest
./gradlew :kotlin:flowdux-remote-node-mediator:jvmTest
./gradlew :kotlin:flowdux-timetravel:jvmTest

# 수정 범위가 넓을 때 전체 테스트
./gradlew jvmTest
```

- 실패 시 → 원인 분석 → 수정 → 재실행 (최초 실행 포함 최대 3회)
- 3회 실패 시 아래 형식으로 보고하고 **워크플로우를 중단하세요. PR을 생성하거나 푸시하지 않습니다.**

```
이슈 #$ARGUMENTS 처리 실패 (자율 모드):
- 브랜치: {branch-name}
- 실패 원인: {테스트 실패 내역 요약}
- 시도 횟수: 3회
- 수동 확인이 필요합니다.
```

### 4-4. 커밋 & 푸시

이슈 문서도 함께 커밋합니다:

```bash
git add {수정된 파일들} docs/issue/$ARGUMENTS/
git commit -m "$(cat <<'EOF'
{type}({scope}): {description}

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
git push -u origin {branch-name}
```

---

## Phase 5: PR 생성 & 리뷰 대응

`.claude/commands/pr.md` 워크플로우를 따릅니다. 단, 자율 모드 규칙을 적용합니다:

- **타겟 브랜치**: `develop` (모든 이슈 유형이 develop을 타겟으로 함 — git-workflow.md 규칙, 사용자 확인 불필요)
- **PR body의 Summary 섹션에 `Closes #$ARGUMENTS` 포함**
- **로컬 코드 리뷰 (pr.md section 1-5)**: CRITICAL은 자동 수정 (최대 2회), WARNING은 로그만 남기고 진행
- **Copilot 리뷰 대응 (pr.md Phase 2 — 코드리뷰 & CI 대응 루프)**: 코멘트 자동 분석 → 수정 필요하면 자동 수정 & 답글, 불필요하면 사유 답글을 사용자 확인 없이 자동 수행
- **CI 실패 대응**: CI 실패 시 로그를 분석하여 수정 → 커밋 → 푸시를 반복 (최초 CI 포함 총 5회까지 시도). 5회 시도 후에도 실패하면 **CI 실패로 중단**하고 Phase 8에 실패 사유를 포함하여 보고
- **머지는 수행하지 않음** — pr.md Phase 3(완료)에서 머지 준비 완료만 보고

### 5-1. 리뷰 로그 기록

PR 워크플로우 진행 중 리뷰 결과를 `docs/issue/$ARGUMENTS/review-log.md`에 기록합니다:

- **로컬 코드 리뷰 (pr.md 1-5)**: Agent 리뷰 결과 — 발견된 문제와 대응 내역
- **Copilot 리뷰 (pr.md Phase 2)**: 라운드별 코멘트와 대응 내역

```markdown
# 리뷰 로그 — 이슈 #{번호}

## 로컬 코드 리뷰

- **리뷰어**: Claude Agent (general-purpose)
- **시점**: PR 생성 전

| # | 심각도 | 파일 | 내용 | 대응 |
|---|--------|------|------|------|
| 1 | CRITICAL/IMPORTANT/WARNING | ... | ... | 수정/스킵 (사유) |

## Copilot 리뷰

- **리뷰어**: GitHub Copilot (`copilot-pull-request-reviewer[bot]`)

### Round {n} (commit: {short-sha})

| # | 파일 | 코멘트 | 대응 |
|---|------|--------|------|
| 1 | ... | ... | 수정/스킵 (사유) |
```

이 파일은 PR 워크플로우 진행과 동시에 작성하며, 커밋에 포함합니다.

---

## Phase 6: 작업 완료 리뷰

PR 생성 & CI 통과 후, `/issue-review $ARGUMENTS`를 Skill로 호출합니다.

```
Skill: issue-review
Args: $ARGUMENTS
```

리뷰에서 즉시 수정 항목이 발견되면 수정 → 커밋 → 푸시합니다.
수정된 내용이 있으면 CI 재확인 후 진행합니다.

---

## Phase 7: 회고

머지 전 회고를 수행합니다. `/issue-retro $ARGUMENTS`를 Skill로 호출합니다.

```
Skill: issue-retro
Args: $ARGUMENTS
```

회고에서 승인된 개선 사항이 있으면 커밋 → 푸시합니다.

---

## Phase 8: 완료 보고

CI 통과 + 코드리뷰 완료 + 작업 리뷰 완료 시:

```
PR #{pr-number} 머지 준비 완료 (자율 모드):
- PR: {pr-url}
- 이슈: #$ARGUMENTS
- 브랜치: {branch-name}
- 문서: docs/issue/$ARGUMENTS/ (plan.md, context.md, review-log.md, result.md)
- CI: 모두 통과
- 코드리뷰: 대응 완료
- 작업 리뷰: 완료 (즉시 수정 {N}건, 이슈 등록 {M}건)
- 회고: 완료 (rule {R}건, mistake {S}건, 워크플로우 {T}건)
- 머지가 필요하면 수동으로 진행하세요.
```

CI 수정 실패로 중단 시:

```
PR #{pr-number} CI 실패로 중단 (자율 모드):
- PR: {pr-url}
- 이슈: #$ARGUMENTS
- 브랜치: {branch-name}
- CI 실패 항목: {실패한 job 이름 목록, 콤마 구분}
- 실패 사유: {간략한 원인}
- 수동 확인이 필요합니다.
```
