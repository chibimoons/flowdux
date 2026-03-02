---
paths:
  - "**/*.md"
  - "kotlin/**/*.kt"
---

# Documentation Consistency Rules

- PR 생성 후 구현이 변경되면 PR description도 최종 구현에 맞게 갱신
- KDoc 코드 예시는 실제 컴파일 가능한 시그니처와 파라미터 사용 (특히 파라미터 추가/삭제 후 예시 점검)
- 버전 변경(gradle.properties) 시 docs/ 하위 문서의 버전 참조도 함께 갱신
- 동일 문서 내에서 용어, API 플래그 스타일(예: `-X POST` vs `--method POST`) 통일
