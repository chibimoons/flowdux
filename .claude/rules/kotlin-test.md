---
paths:
  - "kotlin/**/src/*Test/**/*.kt"
  - "kotlin/**/*Test.kt"
  - "kotlin/**/*Tests.kt"
---

# Kotlin Test Rules

- Connection, HttpClient 등 리소스 생성 시 반드시 `try/finally { resource.disconnect() }` 패턴 사용
- 동시성/Race condition 테스트는 기본적으로 `runBlocking` + `Dispatchers.Default` 사용 권장 (`runTest`는 기본 단일 스레드 테스트 스케줄러를 사용하므로 실제 멀티스레드 race-condition 재현에는 적합하지 않음; 필요 시 실제 디스패처를 조합한 고급 사용은 신중히 적용)
- 고정 `delay()`로 타이밍 대기 금지 — 다음 패턴 사용:
  ```kotlin
  withTimeout(5_000) { while (!condition) { delay(10) } }
  ```
- 테스트 이름은 실제 검증 내용을 정확히 기술 (이름과 assert가 불일치하면 안 됨)
- 코드 변경에는 반드시 대응하는 테스트가 포함되어야 함 — 특히 race condition 수정, 새 파라미터 추가, 에러 핸들링 변경
