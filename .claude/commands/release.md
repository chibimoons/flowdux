# 릴리즈

릴리즈를 수행합니다. 인자로 버전을 받습니다: `/release 1.16.0`

## 1. Git Workflow 규칙 로드

`docs/dev/git-workflow.md`를 Read하여 규칙을 확인하세요.

## 2. GitHub 계정 확인

```bash
gh auth status
```

`chibimoons` 계정이 Active가 아니면 `gh auth switch -u chibimoons`.

## 3. develop 브랜치에서 release 브랜치 생성

```bash
git checkout develop && git pull origin develop
git checkout -b release/<version>
```

## 4. 버전 업데이트

아래 파일들의 버전을 업데이트하세요:

- `gradle.properties` → `flowdux.version=<version>`
- `README.md` → 모든 dependency 버전
- `docs/guide/remote.md` → 모든 dependency 버전
- `docs/guide/timetravel.md` → 모든 dependency 버전
- `docs/guide/getting-started.md` → 모든 dependency 버전
- 기타 버전 참조가 있는 문서 확인: `grep -r "chibimoons:flowdux" .`

## 5. 커밋 & 푸시

```bash
git add <files>
git commit -m "$(cat <<'EOF'
chore: bump version to <version>

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
git push -u origin release/<version>
```

## 6. PR 생성 (main + develop 양쪽)

같은 브랜치에서 `main`과 `develop` 양쪽으로 PR을 생성합니다:

```bash
# main 타겟 (배포용)
gh pr create --base main --title "release: <version>" --body "$(cat <<'EOF'
## Summary
<이번 릴리즈에 포함된 변경 사항>

## Test plan
- [ ] 전체 모듈 테스트 통과
- [ ] 샘플 앱 컴파일 확인

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"

# develop 타겟 (버전 동기화)
gh pr create --base develop --title "chore: sync release <version> to develop" --body "$(cat <<'EOF'
## Summary
- Sync version bump (<version>) to develop

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

```
release/x.x.x
  ├── PR → main    (배포용)
  └── PR → develop (버전 동기화)
```

## 7. 머지 후 태그 & 배포

**양쪽 PR 모두 머지한 후** 태그를 생성합니다:

```bash
git fetch origin main --tags
git tag <version> origin/main
git push origin <version>
```

GitHub Actions(`publish-maven-central.yml`)가 자동 배포합니다 (~25분).

## 8. 배포 확인

```bash
gh run list --workflow=publish-maven-central.yml --limit 1
gh run watch <run-id>
```
