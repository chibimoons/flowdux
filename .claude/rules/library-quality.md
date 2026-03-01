---
paths:
  - "kotlin/**/commonMain/**/*.kt"
---

# Library Code Quality Rules

- `println` / `System.out` 금지 — injectable `onEvent: ((Event) -> Unit)?` 콜백 또는 sealed event class 사용
- behavioral change(예: unbounded → bounded channel) 시 KDoc에 동작 변경 사항 문서화
- `Channel.send()` 또는 `Flow.emit()`이 suspend할 수 있는 경우 KDoc에 명시
