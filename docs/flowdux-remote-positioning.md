# flowdux-remote: WebSocket 영역에서의 포지셔닝

> 설계 문서 — 2026-01-29

## REST와의 관계

flowdux-remote는 REST를 대체하는 것이 아니다. 목적이 다르다.

### REST가 잘하는 것

- 무상태 요청-응답 (CRUD)
- 캐싱 (HTTP 캐시, CDN)
- 브라우저/범용 클라이언트 호환
- 스케일아웃이 단순 (로드밸런서 뒤에 stateless 서버)

### flowdux-remote가 잘하는 것

- 실시간 양방향 상태 동기화
- 서버-클라이언트 간 일관된 상태 모델
- 액션 기반 프로토콜 (무엇이 일어났는지 의미가 명확)
- 클라이언트/서버 각각 로컬 State를 두면서 필요한 부분만 선택적 동기화

### 적합한 유스케이스

- 멀티플레이어 게임
- 실시간 협업 (화이트보드, 문서 공동편집)
- 채팅/메신저
- 라이브 대시보드
- IoT 디바이스 제어

### 공존 모델

REST + flowdux-remote가 공존하는 구조가 자연스럽다.
정적 데이터는 REST, 실시간 상태는 flowdux-remote.

---

## WebSocket 영역에서의 차별점

### 현재 WebSocket 개발의 고통

- 메시지 포맷 직접 설계 (JSON 스키마, 타입 구분)
- 양방향 상태 동기화 직접 구현
- 클라이언트/서버 코드가 완전히 분리되어 계약이 깨지기 쉬움
- 재연결, 상태 복구 등 인프라 코드가 비즈니스 로직보다 많음

### flowdux-remote가 이미 해결하고 있는 것

- **타입 안전한 와이어 계약**: 공유 액션을 `sealed interface`로 정의하고, `ServerSharedAction`/`ClientSharedAction` 마커로 방향을 명시하여 컴파일 타임에 계약 보장
- **방향성이 타입에 녹아있음**: `ServerSharedAction`/`ClientSharedAction` 마커로 어떤 액션이 어느 방향인지 명확
- **인프라 분리**: 미들웨어가 직렬화/라우팅 처리, 개발자는 비즈니스 로직만 작성
- **선택적 상태 동기화**: `serve`로 서버 State에서 필요한 것만 골라 클라이언트에 전송

### 핵심 모델의 차별화

"액션 기반 양방향 프로토콜 + 타입 안전한 공유 계약 + 미들웨어 파이프라인"

이 조합은 Socket.IO나 Ktor raw WebSocket에는 없는 구조적 장점이다.

#### 비교

| | Socket.IO | Ktor WebSocket | flowdux-remote |
|---|---|---|---|
| 타입 안전 계약 | X (문자열 이벤트) | X (raw Frame) | O (sealed interface) |
| 방향성 마커 | X | X | O (ServerShared/ClientShared) |
| 상태 동기화 | 직접 구현 | 직접 구현 | `serve` 한 줄 |
| 미들웨어 파이프라인 | X | X | O (ActionProcessor) |
| 비즈니스 로직 분리 | 낮음 | 낮음 | 높음 |

---

## 현재 상태와 로드맵

### 현재 위치

핵심 모델은 설득력 있고, 단일 세션에서는 이미 동작하는 단계.

구현 완료:
- `ServerRemoteMiddleware` / `ClientRemoteMiddleware`
- `TypedServerConnection` / `TypedClientConnection`
- `ActionCodec` + `MessageCodec` (직렬화 계층)
- `serveState` (서버→클라이언트 상태 동기화)
- 모듈별 독립 State (서버/클라이언트 각각 로컬 State + 선택적 동기화)
- KMP 지원 (Kotlin Multiplatform)

### 게임체인저가 되기 위해 필요한 것

- [ ] 재연결 시 자동 상태 복구
- [ ] 멀티 클라이언트 지원 (현재는 1 connection = 1 store)
- [ ] Room/Channel 추상화
- [ ] 상태 diff 전송 (전체 State 대신 변경분만)
- [ ] 브라우저 클라이언트 (JS/TS SDK)
- [x] boilerplate 감소 (`Store.serve()` — StartListening 내부화, use/serve 패턴)
