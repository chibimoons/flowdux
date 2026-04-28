# Git Workflow Rules

## GitHub 계정 확인 (매번 필수)

```bash
gh auth status  # chibimoons 계정이 Active인지 확인
gh auth switch -u chibimoons  # 아니면 전환
```

## Main 브랜치 머지 규칙 (CI 강제)

`.github/workflows/protect-main.yml`이 소스 브랜치명을 검증한다.

| 허용 브랜치 패턴 | 용도 |
|-----------------|------|
| `release/*` | 릴리즈 배포 |
| `hotfix/*` | 긴급 수정 |
| `docs/*` | 문서 업데이트 |

**그 외 브랜치명(`sync/*`, `feature/*`, `fix/*` 등)은 CI에서 reject됨.**

## PR 타겟 브랜치

| 작업 유형 | 타겟 브랜치 |
|----------|------------|
| feature, fix, docs (일반) | `develop` |
| release, docs (main 직접 반영) | `main` |
| hotfix | `main` + `develop` (양쪽 모두) |

## 핫픽스 프로세스

1. `origin/main`에서 `hotfix/{short-description}` 브랜치 생성
2. 수정 → 커밋
3. `main`으로 PR 생성 → CI 통과 → 머지
4. **같은 브랜치**에서 `develop`으로도 PR 생성 → 머지
5. 필요 시 패치 태그 생성 (e.g. `1.17.1`)

```
hotfix/xxx
  ├── PR → main   (긴급 수정 반영)
  └── PR → develop (develop에도 동기화)
```

## 릴리즈 프로세스

1. `develop`에서 `release/x.x.x` 브랜치 생성
2. `gradle.properties` → `flowdux.version` 업데이트
3. `README.md`, `docs/guide/remote.md`, `docs/guide/timetravel.md`, `docs/guide/getting-started.md` 버전 업데이트
4. **같은 브랜치**에서 `main`과 `develop` 양쪽으로 PR 생성 → 머지
5. 최신 main 동기화: `git checkout main && git pull --ff-only`
6. 태그: `git tag x.x.x && git push origin x.x.x`
7. GitHub Actions 자동 배포 (~25분)

```
release/x.x.x
  ├── PR → main    (배포용)
  └── PR → develop (버전 동기화)
```

## 브랜치 네이밍

| 유형 | 패턴 |
|------|------|
| 기능 | `feature/{issue-number}-{short-description}` |
| 버그 수정 | `fix/{issue-number}-{short-description}` |
| 문서 | `docs/{short-description}` |
| 릴리즈 | `release/{version}` |
| 핫픽스 | `hotfix/{short-description}` |

## 커밋 메시지

```bash
git commit -m "$(cat <<'EOF'
feat(module): description

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

## Git Hooks 설정

프로젝트에 코드 품질을 강제하는 git hooks가 포함되어 있다.

### 설치 (1회)

```bash
make setup
# 또는 직접: git config core.hooksPath .githooks
```

### Hook 목록

| Hook | 시점 | 검증 내용 |
|------|------|----------|
| `pre-commit` | 커밋 전 | `.kt`/`.kts` 파일이 staged 시 `spotlessCheck` 실행 |
| `commit-msg` | 커밋 메시지 작성 후 | Conventional Commits 형식 검증 |
| `pre-push` | 푸시 전 | `detekt` + `compileKotlinJvm` 실행 |

### 유용한 명령어

```bash
make lint      # spotlessCheck + detekt
make format    # spotlessApply (자동 포맷팅)
```

### 포맷팅 실패 시

```bash
./gradlew spotlessApply   # 자동 수정
git add -u                # 수정된 파일 재스테이지
git commit                # 다시 커밋
```
