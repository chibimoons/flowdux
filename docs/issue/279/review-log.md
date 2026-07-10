# 리뷰 로그 — 이슈 #279

## 로컬 코드 리뷰

- **리뷰어**: Agent (general-purpose)
- **시점**: PR 생성 전

| # | 심각도 | 파일 | 내용 | 대응 |
|---|--------|------|------|------|
| 1 | CRITICAL | time_travel_store.dart | `_recordStateChange`에 `previousState` 대신 `timeTravelStore.currentState` 사용 | 스킵 — 의도된 설계. undo/redo 후 내부 Store의 previousState와 TTS의 currentState가 다를 수 있으며, reducer에 입력된 TTS의 currentState가 올바른 previousState. Kotlin 구현과 동일 패턴. |
| 2 | WARNING | time_travel_store.dart | `_recordStateChange`가 AsyncLock 미사용 | 스킵 — 동기 함수이며 Dart 단일 스레드 모델에서 async interleaving 발생 없음 |
| 3 | WARNING | time_travel_store.dart | maxHistorySize 재인덱싱 O(n^2) | 스킵 — Kotlin과 동일 패턴, 기본 크기 100에서 성능 문제 없음 |
| 4 | WARNING | time_travel_store_test.dart | `Future.delayed(50ms)` 사용 — CI에서 flaky 가능 | 스킵 — 기존 테스트 패턴과 동일 (store_test.dart 참조) |
| 5 | WARNING | main.dart (sample) | 미사용 stream subscription 누수 | 수정 — 불필요한 no-op listener 제거 |

## Copilot 리뷰

- **리뷰어**: GitHub Copilot (`copilot-pull-request-reviewer[bot]`)

### Round 1 (commit: 5ab0c2c)

| # | 파일 | 코멘트 | 대응 |
|---|------|--------|------|
| 1 | time_travel_store.dart:191 | `close()` 비멱등 — BehaviorSubject.close() 이중호출 문제 | 수정 — `_stateSubject.isClosed` 체크 추가 |
| 2 | time_travel_store_test.dart:21 | `store.close()` await 누락 | 수정 — `await store.close()` 로 변경 |
| 3 | review-log.md:11 | 테이블 `||` 포맷 오류 지적 | 스킵 — 실제 파일 확인 시 정상 마크다운 (`| # |` 형식). Copilot 오탐. |

### Round 2 (commit: f822396)

| # | 파일 | 코멘트 | 대응 |
|---|------|--------|------|
| 1 | time_travel_store.dart:66 | `initialState as S` cast — 검증 전에 TypeError 발생 가능 | 수정 — 생성자를 `seedState` 파라미터로 리팩터링. 팩토리에서 미리 계산한 seed를 전달하여 cast 제거. |
| 2 | time_travel_store.dart:292 | undo/redo 후 내부 Store의 currentState가 middleware와 불일치 | 스킵 — Kotlin 구현과 동일한 알려진 제한. TimeTravelStore는 디버깅 도구이며, middleware 상태 동기화는 향후 개선 사항. |
| 3 | time_travel_store_test.dart:12 | middleware 동작 테스트 누락 | 스킵 — Kotlin 테스트 스위트와 1:1 포팅 범위. middleware 테스트는 향후 이슈로 추적 가능. |

### Round 3 (commit: 3a4014b)

| # | 파일 | 코멘트 | 대응 |
|---|------|--------|------|
| 1 | time_travel_store.dart:123 | plan.md에 "close 후 dispatch throws"라고 기술되어 있으나 실제 동작은 무시 | 수정 — plan.md 문구를 실제 동작에 맞게 수정 |

## 작업 완료 리뷰 (issue-review)

- **리뷰어**: Agent (general-purpose)
- **시점**: PR 머지 전

| # | 항목 | 평가 | 상세 |
|---|------|------|------|
| 1 | 완성도 | ✅ | plan.md 6개 Step 전부 완료 |
| 2 | 정확성 | ✅ | 이슈 요구사항 전체 충족, Kotlin 기능 대등성 확보 |
| 3 | 코드 품질 | ✅ | 기존 컨벤션 준수 (Store 패턴, 팩토리, import 스타일) |
| 4 | 테스트 | ✅ | 16/16 통과, 전체 121개 회귀 없음 |
| 5 | 문서 일관성 | ✅ | context.md 6개 결정사항 전부 반영 |

### 즉시 수정

없음.

### 이슈 등록

| # | 이슈 | 제목 |
|---|------|------|
| 1 | #284 | feat(dart): sync middleware state with TimeTravelStore after undo/redo |
| 2 | #285 | test(dart): add middleware integration tests for TimeTravelStore |
