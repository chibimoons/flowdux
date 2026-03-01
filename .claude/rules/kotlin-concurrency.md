---
paths:
  - "kotlin/**/*.kt"
---

# Kotlin Concurrency Rules

- `catch (e: Exception)` 사용 시 반드시 `CancellationException`을 먼저 rethrow할 것
  ```kotlin
  try { ... } catch (e: CancellationException) { throw e } catch (e: Exception) { ... }
  ```
- 여러 스레드/디스패처에서 동시에 접근하는 mutable 프로퍼티에는 `@Volatile` 또는 `Atomic*` 사용
- 공유 `Channel`은 close하면 재사용 불가 — 재연결이 필요한 경우 Job 취소 또는 per-connect Channel 생성
- `trySend()` 결과를 무시하지 말 것 — 실패 시 로깅 또는 이벤트 콜백으로 처리
- `Channel.UNLIMITED` 지양 — `Channel.BUFFERED` 사용하고 backpressure 동작을 KDoc에 문서화
