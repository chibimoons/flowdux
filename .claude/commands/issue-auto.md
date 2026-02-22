---
description: "이슈 자율 처리 — 분석 → 코드 수정 → PR 생성까지 확인 없이 자율 진행 (머지 제외)"
argument-hint: <이슈번호>
allowed-tools: [Bash, Read, Edit, Write, Glob, Grep, Task]
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

## Phase 2: 이슈 분석 & 계획

### 2-1. 이슈 정보 수집

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

### 2-2. 관련 코드 탐색

Task(Explore) 서브에이전트로 이슈에서 요구하는 수정 범위를 파악하세요:
- 어떤 파일을 수정해야 하는지
- 기존 코드 구조와 패턴 파악
- 영향 범위 분석

### 2-3. 수정 계획 출력 (확인 없이 진행)

분석 결과를 아래 표 형태로 **출력만** 하고 바로 Phase 3으로 진행합니다:

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

## Phase 3: 브랜치 생성 & 코드 수정

### 3-1. develop 최신화 & 브랜치 생성

```bash
git fetch origin develop
git checkout -b {branch-name} origin/develop
```

### 3-2. 코드 수정

Phase 2에서 출력한 계획에 따라 코드를 수정합니다.

### 3-3. 테스트 실행

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

### 3-4. 커밋 & 푸시

```bash
git add {수정된 파일들}
git commit -m "$(cat <<'EOF'
{type}({scope}): {description}

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
git push -u origin {branch-name}
```

---

## Phase 4: PR 생성 & 리뷰 대응

`.claude/commands/pr.md` 워크플로우를 따릅니다. 단, 자율 모드 규칙을 적용합니다:

- **타겟 브랜치**: `develop` (모든 이슈 유형이 develop을 타겟으로 함 — git-workflow.md 규칙, 사용자 확인 불필요)
- **PR body의 Summary 섹션에 `Closes #$ARGUMENTS` 포함**
- **로컬 코드 리뷰 (pr.md section 1-5)**: CRITICAL은 자동 수정 (최대 2회), WARNING은 로그만 남기고 진행
- **Copilot 리뷰 대응 (pr.md Phase 2 — 코드리뷰 & CI 대응 루프)**: 코멘트 자동 분석 → 수정 필요하면 자동 수정 & 답글, 불필요하면 사유 답글을 사용자 확인 없이 자동 수행
- **머지는 수행하지 않음** — pr.md Phase 3(완료)에서 머지 준비 완료만 보고

---

## Phase 5: 완료 보고

```
PR #{pr-number} 머지 준비 완료 (자율 모드):
- PR: {pr-url}
- 이슈: #$ARGUMENTS
- 브랜치: {branch-name}
- CI: 통과 여부
- 코드리뷰: 대응 완료 여부
- 머지가 필요하면 수동으로 진행하세요.
```
