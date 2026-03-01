---
paths:
  - "**/*.sh"
  - ".github/**/*.yml"
  - ".claude/**/*.sh"
---

# Script & CI Rules

- 셸 스크립트에서 API 호출(gh api 등) 결과에 반드시 fallback 제공: `count=${count:-0}`
- CI 워크플로에서 JDK distribution(temurin, zulu 등)은 프로젝트 전체에서 통일
- 셸 스크립트에서 외부 도구(jq, gh 등) 사용 전 `command -v` 로 존재 확인
