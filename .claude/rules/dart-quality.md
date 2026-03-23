---
paths:
  - "dart/**/*.dart"
---

# Dart Code Quality Rules

- 커밋 전 `dart format .`을 실행하여 포맷 검증 — CI의 `dart format --set-exit-if-changed .` 실패 방지
- `close()` / `dispose()` 메서드는 반드시 멱등성 보장 — `isClosed` 또는 상태 플래그 체크 후 정리 수행
- BehaviorSubject, StreamController 등 리소스 close 시 `isClosed` 체크 필수
- nullable 타입을 non-null로 캐스팅(`as S`) 금지 — 팩토리 또는 상위에서 미리 계산한 값을 전달
