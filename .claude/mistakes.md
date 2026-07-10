# Mistakes Log

반복되는 실수 패턴을 기록합니다. `/issue-retro`에서 발견된 항목이 추가됩니다.

## 형식

각 항목은 아래 형식을 따릅니다:

- **상황**: 어떤 상황에서 발생했는지
- **원인**: 왜 발생했는지
- **규칙**: 앞으로 어떻게 방지할지

---

### 1. Dart format 미실행으로 CI 실패

- **상황**: Dart 소스 파일 작성 후 `dart format` 실행 없이 커밋 및 푸시
- **원인**: Kotlin에는 `pre-commit` hook의 `spotlessCheck`가 있으나, Dart에는 동등한 로컬 검증이 없음. 포맷팅을 CI에만 의존
- **규칙**: Dart 파일 수정 시 커밋 전 반드시 `dart format .` 실행. `.claude/rules/dart-quality.md` 참조

### 2. close() 멱등성 누락

- **상황**: `close()` 메서드에서 `_stateSubject.close()`를 `isClosed` 체크 없이 직접 호출하여 이중 close 시 에러 발생 가능
- **원인**: 리소스 정리 메서드의 멱등성을 기본 패턴으로 인식하지 못함
- **규칙**: `close()` / `dispose()` 구현 시 항상 `isClosed` 체크를 포함. `.claude/rules/dart-quality.md` 참조
