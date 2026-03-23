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
