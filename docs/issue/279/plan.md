# 작업계획서 — 이슈 #279: feat(dart): implement TimeTravelStore module

## 개요

Kotlin의 `flowdux-timetravel` 모듈을 Dart로 포팅합니다. 디버깅 시 상태 히스토리를 추적하고 undo/redo/jumpTo를 지원하는 TimeTravelStore를 구현합니다.

## 브랜치 정보

- 브랜치: `feature/279-dart-timetravel`
- 타겟: `develop`
- 유형: `feat`
- 스코프: `flowdux`

## 수정 대상

| # | 파일 | 변경 내용 | 이유 |
|---|------|----------|------|
| 1 | `dart/flowdux/lib/src/timetravel/state_snapshot.dart` | StateSnapshot 데이터 클래스 생성 | 히스토리 항목 표현 |
| 2 | `dart/flowdux/lib/src/timetravel/time_travel_store.dart` | TimeTravelStore 래퍼 클래스 생성 | undo/redo/jumpTo 핵심 로직 |
| 3 | `dart/flowdux/lib/src/timetravel/create_time_travel_store.dart` | 팩토리 함수 생성 | Store 생성 진입점 |
| 4 | `dart/flowdux/lib/flowdux.dart` | barrel export에 timetravel 추가 | 외부에서 import 가능하도록 |
| 5 | `dart/flowdux/test/timetravel/time_travel_store_test.dart` | 16+ 테스트 케이스 작성 | Kotlin 테스트 포팅 |
| 6 | `dart/flowdux/test/timetravel/test_fixtures.dart` | 테스트용 Counter State/Action/Reducer | 테스트 헬퍼 |
| 7 | `dart/samples/timetravel/bin/main.dart` | CLI 샘플 앱 | TimeTravelStore 사용법 시연 |
| 8 | `dart/samples/timetravel/pubspec.yaml` | 샘플 앱 패키지 설정 | 의존성 관리 |

## 단계별 계획

### Step 1: StateSnapshot 데이터 클래스

- [ ] `dart/flowdux/lib/src/timetravel/state_snapshot.dart` 생성
  - `StateSnapshot<S, A extends Action>` — immutable 클래스
  - 필드: `index`, `action` (nullable), `previousState` (nullable), `currentState`, `timestamp` (DateTime)
  - `toString()`, `==`, `hashCode` 구현

### Step 2: TimeTravelStore 핵심 구현

- [ ] `dart/flowdux/lib/src/timetravel/time_travel_store.dart` 생성
  - `TimeTravelStore<S, A extends Action>` 래퍼 클래스
  - 내부 Store를 감싸고 StoreLogger를 통해 상태 변경 가로채기
  - 프로퍼티: `state` (Stream), `currentState`, `history`, `currentIndex`, `canUndo`, `canRedo`, `isClosed`
  - 메서드: `dispatch()`, `undo()`, `redo()`, `jumpTo()`, `reset()`, `clear()`, `close()`
  - `AsyncLock` 사용하여 동시 접근 방지
  - `maxHistorySize` 초과 시 오래된 항목 제거
  - dispatch 시 현재 인덱스 이후 히스토리 truncate
  - BehaviorSubject로 상태 스트림 관리

### Step 3: createTimeTravelStore 팩토리 함수

- [ ] `dart/flowdux/lib/src/timetravel/create_time_travel_store.dart` 생성
  - `createTimeTravelStore()` — 새 Store 생성 (initialState 기반)
  - `createTimeTravelStoreFromHistory()` — 기존 히스토리로 복원
  - 내부적으로 Store 생성 후 TimeTravelStore로 감싸기

### Step 4: Barrel export 업데이트

- [ ] `dart/flowdux/lib/flowdux.dart`에 timetravel 파일 export 추가

### Step 5: 테스트 작성

- [ ] `dart/flowdux/test/timetravel/test_fixtures.dart` — CounterState, CounterAction, counterReducer
- [ ] `dart/flowdux/test/timetravel/time_travel_store_test.dart` — Kotlin 16개 테스트 포팅:
  1. 초기 상태가 history[0]에 기록
  2. dispatch 시 히스토리에 상태 기록
  3. undo/redo 왕복 동작
  4. jumpTo 정상 동작
  5. jumpTo 경계값 (0, 끝, 범위 밖)
  6. dispatch 후 undo → dispatch → redo 불가 (히스토리 truncate)
  7. reset → 초기 상태로 복원
  8. clear → 히스토리 초기화, 현재 상태 유지
  9. maxHistorySize 초과 시 오래된 항목 제거
  10. close 후 dispatch throws
  11. 타임스탬프 기록 확인
  12. 히스토리 인덱스 순차 확인
  13. fromHistory 복원
  14. fromHistory 후 dispatch
  15. 빈 initialHistory → ArgumentError
  16. canUndo/canRedo 상태 확인

### Step 6: CLI 샘플 앱

- [ ] `dart/samples/timetravel/pubspec.yaml` 생성
- [ ] `dart/samples/timetravel/bin/main.dart` 생성
  - Counter 상태를 TimeTravelStore로 관리
  - CLI 인터랙티브 명령: +/-, u(undo), r(redo), j(jumpTo), h(history), c(clear), q(quit)

## 검증 방법

- `cd dart/flowdux && dart test test/timetravel/` — 전체 timetravel 테스트 실행
- `cd dart/flowdux && dart test` — 전체 패키지 테스트 (기존 테스트 깨지지 않음 확인)
- `cd dart/samples/timetravel && dart run` — 샘플 앱 실행 확인
- `dart analyze dart/flowdux` — 정적 분석 통과

## 진행 상태

| Step | 상태 | 비고 |
|------|------|------|
| Step 1 | ✅ 완료 | StateSnapshot |
| Step 2 | ✅ 완료 | TimeTravelStore (factory 함수 포함) |
| Step 3 | ✅ 완료 | Factory functions (Step 2에 통합) |
| Step 4 | ✅ 완료 | Barrel export |
| Step 5 | ✅ 완료 | Tests (16개) |
| Step 6 | ✅ 완료 | CLI sample |
