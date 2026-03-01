---
paths:
  - "kotlin/**/main/**/*.kt"
  - "kotlin/**/commonMain/**/*.kt"
---

# API Compatibility Rules

- public/protected 메서드 제거 시 `@Deprecated(level = DeprecationLevel.WARNING)` 래퍼를 먼저 추가 — 즉시 삭제 금지
- interface에 새 메서드 추가 시 반드시 default 구현 제공 (외부 구현체 Breaking Change 방지)
- commonMain 라이브러리 코드에서 `println` 금지 — injectable callback (`onEvent`, `onError`) 또는 sealed event class로 대체
