# 컨텍스트 — 이슈 #279: feat(dart): implement TimeTravelStore module

## 배경

Kotlin FlowDux에는 `flowdux-timetravel` 모듈이 있어 디버깅 시 상태 히스토리를 추적하고 undo/redo/jumpTo를 지원합니다. Dart 버전에는 아직 이 기능이 없으며, Kotlin 구현을 포팅하여 Dart/Flutter 개발자도 동일한 디버깅 기능을 사용할 수 있도록 합니다.

## 관련 자료

- GitHub 이슈: #279
- Kotlin 구현: `kotlin/timetravel/src/commonMain/kotlin/io/flowdux/timetravel/`
  - `TimeTravelStore.kt` — 핵심 래퍼 클래스 (235줄)
  - `StateSnapshot.kt` — 상태 스냅샷 데이터 클래스
- Kotlin 테스트: `kotlin/timetravel/src/commonTest/kotlin/io/flowdux/timetravel/TimeTravelStoreTest.kt` (16개 테스트)
- Dart 기존 코어: `dart/flowdux/lib/src/` — Store, Action, Reducer, Middleware 등
- 설계 문서: `docs/design/plan-dart-version.md`
- 사용 가이드: `docs/guide/timetravel.md` (Kotlin 버전)

## 논의 사항

### 2026-03-24: 패키지 구조 결정

Kotlin은 별도 모듈(`flowdux-timetravel`)로 분리되어 있으나, Dart 버전은 `dart/flowdux` 패키지 내 `lib/src/timetravel/` 서브디렉토리로 포함하기로 결정. 이유: Dart 패키지는 아직 초기 단계이고, 별도 pub.dev 패키지로 분리하기엔 이른 시점.

### 2026-03-24: Kotlin ↔ Dart 매핑

| Kotlin | Dart | 비고 |
|--------|------|------|
| `StateFlow<S>` | `BehaviorSubject<S>` (rxdart) | 동기 접근 + 스트림 |
| `Mutex` | `AsyncLock` | 기존 구현 재사용 |
| `suspend fun` | `Future<T>` | |
| `Long` (epochMillis) | `DateTime` | Dart 관용적 타입 |
| `CoroutineScope` | 불필요 | Dart 단일 스레드 |
| Factory overloading | `createTimeTravelStore` + `createTimeTravelStoreFromHistory` | Dart는 함수 오버로딩 없음 |

### 2026-03-24: 샘플 앱 추가

이슈 #279에 CLI 기반 Counter 샘플 앱 포함하기로 결정. `dart/samples/timetravel/` 위치에 생성. 인터랙티브 명령으로 +/-, undo, redo, jumpTo, history, clear, quit 지원.

## 결정 사항

| # | 결정 | 이유 | 날짜 |
|---|------|------|------|
| 1 | `dart/flowdux` 패키지 내 서브디렉토리로 구현 | 별도 패키지 분리는 이른 시점 | 2026-03-24 |
| 2 | `BehaviorSubject` 사용 (rxdart) | 기존 Store와 동일한 패턴, 동기 접근 필요 | 2026-03-24 |
| 3 | `AsyncLock` 재사용 | 기존 `dart/flowdux/lib/src/util/async_lock.dart` 활용 | 2026-03-24 |
| 4 | `StoreLogger` 통해 상태 변경 가로채기 | Kotlin과 동일한 패턴 | 2026-03-24 |
| 5 | 함수 이름 분리: `createTimeTravelStore` + `createTimeTravelStoreFromHistory` | Dart 함수 오버로딩 불가 | 2026-03-24 |
| 6 | CLI 샘플 앱 포함 | 사용법 시연 및 사용자 체험 | 2026-03-24 |

## 제약 조건

- Dart는 단일 스레드 (isolate 기반) — 진정한 동시성 제어 불필요, async interleaving만 방지
- rxdart 0.28.0 의존성 (기존 flowdux 패키지에 이미 포함)
- Dart 3.0+ 필요 (sealed class 등)
- `freezed` 패키지 미사용 — 수동 `==`/`hashCode` 구현
