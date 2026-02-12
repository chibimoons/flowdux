# Claude Code 사용 가이드

FlowDux 프로젝트에서 Claude Code를 효과적으로 사용하기 위한 조사 결과와 가이드.

## 설정 파일 구조

### 자동 로드되는 파일

| 파일 | 로드 시점 | 제한 | 용도 |
|------|----------|------|------|
| `CLAUDE.md` (프로젝트 루트) | 항상 (세션 시작) | 없음 | 핵심 규칙, 프로젝트 컨텍스트 |
| `CLAUDE.local.md` (프로젝트 루트) | 항상 (세션 시작) | 없음 | 로컬 전용 설정 (gitignore 대상) |
| `~/.claude/CLAUDE.md` | 항상 (세션 시작) | 없음 | 사용자 전역 설정 |
| `MEMORY.md` (auto memory) | 항상 (세션 시작) | **200줄 초과 시 truncate** | 세션 간 학습 내용 유지 |

### Lazy-load되는 파일

| 파일 | 로드 시점 | 용도 |
|------|----------|------|
| 하위 디렉토리 `CLAUDE.md` | **해당 디렉토리의 파일을 Read할 때** | 모듈별 컨텍스트 |

**주의:** Bash 명령어(git, gradle 등)만 실행할 때는 하위 CLAUDE.md가 로드되지 않는다.
파일을 Read 도구로 읽어야 트리거된다.

### 로드 순서 (상위 → 하위)

```
~/.claude/CLAUDE.md                    ← 전역 (항상)
프로젝트루트/CLAUDE.md                  ← 프로젝트 (항상)
프로젝트루트/CLAUDE.local.md            ← 로컬 전용 (항상)
.claude/projects/.../memory/MEMORY.md  ← 메모리 (항상, 200줄 제한)
하위디렉토리/CLAUDE.md                  ← lazy-load (파일 Read 시)
```

## 토큰 최적화 전략

### 원칙

CLAUDE.md는 **매 질의마다 토큰을 소비**한다.
내용이 길수록 매번 더 많은 토큰이 사용되므로, 핵심만 간결하게 유지해야 한다.

### 루트 CLAUDE.md에 넣어야 하는 것

항상 필요한 규칙만:
- 커밋 메시지 포맷
- 브랜치 전략 / main 머지 규칙 (git 명령어 시 참조)
- GitHub 계정 확인 규칙
- 프로젝트 구조 개요
- 핵심 개념 (Key Concepts)

### 하위 CLAUDE.md에 분리할 수 있는 것

해당 모듈 파일을 다룰 때만 필요한 상세 컨텍스트:
- `kotlin/remote/CLAUDE.md` — remote 모듈 아키텍처, 미들웨어 동작 원리
- `.github/CLAUDE.md` — CI 워크플로우 수정 시 참고 사항

### 분리할 수 없는 것 (루트에 유지)

**Bash 명령어로만 수행되는 작업의 규칙**은 하위 디렉토리에 넣어도 로드되지 않는다:
- 브랜치 생성/전환 규칙 → 루트
- PR 생성 규칙 → 루트
- git push 전 계정 확인 → 루트
- 릴리즈 프로세스 → 루트

## MEMORY.md vs CLAUDE.md

| 항목 | CLAUDE.md | MEMORY.md |
|------|-----------|-----------|
| 위치 | 프로젝트 루트 (git 관리) | `.claude/projects/...` (로컬) |
| 공유 | 팀 전체 | 본인만 |
| 용도 | 프로젝트 규칙 (강제) | 학습 내용, 실수 방지 노트 |
| 수정 | 사용자가 직접 or PR | Claude가 자동 업데이트 가능 |
| 제한 | 없음 (but 길수록 토큰↑) | 200줄 |

### 사용 가이드

- **CLAUDE.md**: "이렇게 해라" (규칙)
- **MEMORY.md**: "이런 적 있었다" (경험)

예시:
- CLAUDE.md: "`release/*`, `hotfix/*`, `docs/*`만 main 머지 가능"
- MEMORY.md: "`sync/*`로 main PR 만들면 CI fail — CLAUDE.md 참조"

## 컨텍스트 로드 전략: Git 작업 시 전문 규칙 주입

### 문제

Git 작업(PR 생성, push, 릴리즈)은 Bash 명령어로 수행된다.
하위 디렉토리 CLAUDE.md는 파일 Read 시에만 로드되므로, git 규칙을 분리하면 로드되지 않는다.
하지만 루트 CLAUDE.md에 모든 규칙을 넣으면 매 질의마다 토큰 낭비.

### 해결 방법

#### 방법 1: CLAUDE.md에 "읽어라" 지시 (가장 단순)

루트 CLAUDE.md에 1줄만 추가:
```
- PR 생성, main 머지, 릴리즈 작업 전 반드시 `docs/dev/git-workflow.md`를 Read할 것
```

- 토큰: 1줄만 상시 소비
- 상세 규칙은 별도 파일에 (필요할 때만 Read로 로드)
- 단점: Claude가 규칙을 안 지킬 수 있음

#### 방법 2: Hook (자동 트리거)

Claude Code Hooks를 사용하여 특정 도구 실행 시 자동으로 규칙 주입.
`git push`, `gh pr create` 등 감지 시 규칙 파일 내용이 피드백으로 들어옴.

- 장점: 자동, 누락 불가
- 단점: Hook API 스펙 확인 필요, 필터링 로직 필요

> Hook 스펙 조사 결과: [아래 섹션 참조](#hooks-조사-결과)

#### 방법 3: Custom Skill (사용자 트리거)

`/release`, `/pr` 같은 커스텀 슬래시 커맨드를 만들어서 실행 시 git 규칙 로드 후 작업 수행.

- 장점: 사용자가 의도적으로 호출, 명확한 워크플로우
- 단점: 자연어 요청("PR 만들어줘")에는 트리거되지 않음

#### 권장 조합

| 계층 | 역할 | 토큰 비용 |
|------|------|----------|
| CLAUDE.md (1줄) | "PR/릴리즈 전 `git-workflow.md` Read 필수" | 최소 (상시) |
| Hook (안전망) | `git push`/`gh pr` 감지 시 규칙 자동 주입 | 필요할 때만 |
| `docs/dev/git-workflow.md` | 상세 규칙 전문 | Read 시만 |

이중 안전장치: Claude가 규칙을 지키면 Read로 로드, 안 지키면 Hook이 자동 주입.

---

## Hooks 조사 결과

### 설정 위치

| 위치 | 범위 | 공유 |
|------|------|------|
| `~/.claude/settings.json` | 전역 (모든 프로젝트) | X |
| `.claude/settings.json` | 프로젝트 | O (git commit 가능) |
| `.claude/settings.local.json` | 프로젝트 (개인) | X (gitignore) |

### Hook 이벤트 종류

| 이벤트 | 시점 | 차단 가능 |
|--------|------|----------|
| `PreToolUse` | 도구 실행 전 | **O** |
| `PostToolUse` | 도구 실행 후 | X |
| `UserPromptSubmit` | 사용자 입력 후, 처리 전 | O |
| `Stop` | Claude 응답 완료 시 | O |
| `SessionStart` | 세션 시작 시 | X |
| `Notification` | 알림 필요 시 | X |

### Hook 입력 (stdin JSON)

`PreToolUse` 시 stdin으로 받는 데이터:

```json
{
  "session_id": "abc123",
  "cwd": "/home/user/my-project",
  "tool_name": "Bash",
  "tool_input": {
    "command": "git push origin main"
  }
}
```

`tool_input.command`로 **실행될 bash 명령어를 정확히 알 수 있다.**

### Hook 출력 & 차단

| Exit Code | 의미 | 효과 |
|-----------|------|------|
| `0` | 허용 | 도구 실행 진행 |
| `2` | 차단 | 도구 실행 **차단**, stderr가 Claude에게 피드백 |
| 기타 | 비차단 에러 | stderr는 verbose 모드에서만 표시 |

- **stdout**: JSON 구조화 출력 (exit 0 시)
- **stderr**: Claude에게 피드백으로 전달 (exit 2 시)

### Matcher (필터링)

```json
{
  "matcher": "Bash",
  "hooks": [...]
}
```

- 정규식 지원: `"Bash"`, `"Edit|Write"`, `"mcp__.*"`
- `tool_name`에 대해 매칭

### Hook 핸들러 타입

| 타입 | 설명 |
|------|------|
| `command` | 셸 명령어 실행 (기본) |
| `prompt` | Claude 모델에 single-turn 질의 |
| `agent` | 도구 접근 가능한 서브에이전트 생성 |

### 구현 예시: Git 규칙 자동 주입

**스크립트: `.claude/hooks/git-workflow-guard.sh`**

```bash
#!/bin/bash
INPUT=$(cat)
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command')

# git push, gh pr create, git tag 감지
if echo "$COMMAND" | grep -qE "git push|gh pr create|git tag"; then
  RULES_FILE="$CLAUDE_PROJECT_DIR/docs/dev/git-workflow.md"
  if [ -f "$RULES_FILE" ]; then
    echo "[Git Workflow Rules]" >&2
    cat "$RULES_FILE" >&2
    echo "" >&2
  fi
fi

# 항상 허용 (규칙 주입만, 차단은 안 함)
exit 0
```

**설정: `.claude/settings.json`**

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/git-workflow-guard.sh"
          }
        ]
      }
    ]
  }
}
```

**동작:**
1. Claude가 `Bash` 도구로 명령어 실행 시 hook 발동
2. 명령어에 `git push`, `gh pr create`, `git tag`가 포함되면
3. `docs/dev/git-workflow.md` 내용이 stderr로 출력
4. Claude에게 피드백으로 전달 → 규칙 인지
5. exit 0이므로 명령어는 정상 실행

### 차단 예시 (필요 시)

```bash
# exit 2로 변경하면 명령어 차단
if echo "$COMMAND" | grep -qE "git push.*--force"; then
  echo "Blocked: force push는 허용되지 않습니다." >&2
  exit 2  # 차단
fi
```

### 디버깅

```bash
# Hook 실행 상세 로그
claude --debug

# verbose 모드 (실행 중 Ctrl+O)
# hook stderr 출력 확인 가능
```

### 관리

```
/hooks    # Claude Code 내 인터랙티브 hook 관리 메뉴
```

---

## 참고

- [공식 문서: Hooks Guide](https://code.claude.com/docs/en/hooks-guide)
- [공식 문서: Hooks Reference](https://code.claude.com/docs/en/hooks)
- [공식 문서: Memory Management](https://code.claude.com/docs/en/memory.md)
- [공식 문서: Settings](https://code.claude.com/docs/en/settings.md)
- [공식 예시: Bash Command Validator](https://github.com/anthropics/claude-code/blob/main/examples/hooks/bash_command_validator_example.py)
