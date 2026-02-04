# FlowDux Remote: 확장 가능한 영속성 아키텍처

> **Design Document** — flowdux-remote에서 DB 영속성을 처리하면서 수평 확장이 가능한 아키텍처 설계

## 1. 개요

### 1.1 문제 정의

flowdux-remote 기반 서버에서 상태를 DB에 저장해야 할 때:
- 매 액션마다 DB 쓰기 → 지연 시간 증가, DB 병목
- 실시간 게임/채팅에서 높은 쓰기 빈도 감당 어려움
- 단일 서버 → 다중 서버 확장 시 상태 동기화 필요

### 1.2 목표

1. **낮은 지연시간**: 실시간 응답성 유지
2. **데이터 안전성**: 중요 데이터 유실 방지
3. **수평 확장**: 단일 서버에서 시작해 점진적 확장 가능
4. **단순한 초기 구현**: 복잡도는 필요할 때만 추가

---

## 2. 아키텍처 진화 단계

### Phase 1: 단일 서버 (초기)

```
┌─────────────────────────────────────────────────┐
│                 Single Server                   │
│                                                 │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐       │
│  │  Nginx  │ → │  Store  │ → │  Redis  │       │
│  │  (LB)   │   │ Server  │   │         │       │
│  └─────────┘   └─────────┘   └─────────┘       │
│                     │                           │
│                     ↓                           │
│               ┌─────────┐                       │
│               │   DB    │                       │
│               └─────────┘                       │
└─────────────────────────────────────────────────┘
```

**특징:**
- 모든 컴포넌트가 단일 서버에서 실행
- Redis는 상태 버퍼 및 캐시 역할
- DB는 영구 저장소

**장점:**
- 운영 단순
- 네트워크 지연 최소
- 디버깅 용이

### Phase 2: Store 서버 수평 확장

```
                      ┌─────────┐
                      │  Nginx  │
                      │  (LB)   │
                      └────┬────┘
             ┌─────────────┼─────────────┐
             ↓             ↓             ↓
        ┌─────────┐   ┌─────────┐   ┌─────────┐
        │ Store 1 │   │ Store 2 │   │ Store 3 │
        └────┬────┘   └────┬────┘   └────┬────┘
             └─────────────┼─────────────┘
                           ↓
                      ┌─────────┐
                      │  Redis  │  ← Pub/Sub + 상태 저장
                      └────┬────┘
                           ↓
                      ┌─────────┐
                      │   DB    │
                      └─────────┘
```

**특징:**
- Store 서버만 수평 확장
- Redis Pub/Sub로 서버 간 액션 브로드캐스트
- Sticky session으로 같은 Room은 같은 서버로

### Phase 3: 완전 분리

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│ Store 1  │     │ Store 2  │     │ Store 3  │
└────┬─────┘     └────┬─────┘     └────┬─────┘
     └────────────────┼────────────────┘
                      ↓
              ┌──────────────┐
              │ Redis Cluster│
              └──────┬───────┘
                     ↓
              ┌──────────────┐
              │  DB Cluster  │
              └──────────────┘
```

**특징:**
- 각 컴포넌트 독립 확장
- Redis Cluster로 고가용성
- DB 읽기 복제본 분리 가능

---

## 3. 영속성 전략

### 3.1 전략 비교

| 항목 | 직접 DB 쓰기 | Redis 버퍼 | Event Sourcing |
|------|-------------|-----------|----------------|
| 지연시간 | 높음 (DB I/O) | 낮음 | 낮음 |
| 데이터 유실 위험 | 없음 | Redis 장애 시 | 로그 장애 시 |
| 구현 복잡도 | 낮음 | 중간 | 높음 |
| 확장성 | DB 병목 | 좋음 | 매우 좋음 |
| 일관성 | 강함 | 최종적 | 최종적 |

### 3.2 권장: Write-Behind 패턴 (Redis 버퍼)

```
Client → WebSocket → Store → Redis → (async) → DB
                              ↑
                        즉시 응답
```

**동작 방식:**
1. 액션 처리 후 상태를 Redis에 즉시 저장
2. 변경된 Room을 "dirty" 집합에 마킹
3. 별도 워커가 주기적으로 dirty Room을 DB에 동기화
4. 동기화 완료 후 dirty 마킹 제거

### 3.3 Hybrid 접근 (중요도별 분리)

```kotlin
sealed interface GameAction : Action {
    // 중요: 직접 DB 저장 (결제, 아이템 획득)
    data class PurchaseItem(val itemId: String) : GameAction, CriticalAction

    // 덜 중요: Redis 버퍼 (위치, 상태 변경)
    data class Move(val x: Int, val y: Int) : GameAction, BufferedAction
}
```

---

## 4. 구현 상세

### 4.1 영속성 미들웨어

```kotlin
/**
 * Redis Write-Behind 패턴을 구현하는 미들웨어
 *
 * - 상태 변경 시 Redis에 즉시 저장
 * - dirty 플래그 설정으로 비동기 DB 동기화 트리거
 */
class PersistenceMiddleware<S : State, A : Action>(
    private val redis: RedisClient,
    private val roomId: String,
) : Middleware<S, A> {

    override val name = "Persistence"
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    override fun process(
        getState: () -> S,
        action: A
    ): Flow<A> = flow {
        emit(action)

        // PersistableAction만 처리
        if (action is PersistableAction) {
            val state = getState()

            // 1. Redis에 즉시 저장
            redis.set(
                key = "state:$roomId",
                value = state.serialize(),
                expiration = 24.hours
            )

            // 2. dirty 플래그 설정 (워커가 DB 동기화)
            redis.sadd("dirty:rooms", roomId)
        }
    }
}

/** 영속화 대상 액션 마커 */
interface PersistableAction
```

### 4.2 DB 동기화 워커

```kotlin
/**
 * dirty Room들을 주기적으로 DB에 동기화하는 워커
 *
 * - 별도 코루틴에서 실행
 * - 배치 처리로 DB 부하 최소화
 */
class DbSyncWorker(
    private val redis: RedisClient,
    private val db: Database,
    private val syncInterval: Duration = 5.seconds,
    private val batchSize: Int = 100,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    syncDirtyRooms()
                } catch (e: Exception) {
                    logger.error("Sync failed", e)
                }
                delay(syncInterval)
            }
        }
    }

    private suspend fun syncDirtyRooms() {
        // 1. dirty Room 목록 가져오기 (최대 batchSize개)
        val dirtyRooms = redis.spop("dirty:rooms", batchSize)

        if (dirtyRooms.isEmpty()) return

        // 2. 각 Room 상태를 DB에 저장
        val states = dirtyRooms.mapNotNull { roomId ->
            redis.get("state:$roomId")?.let { roomId to it }
        }

        // 3. 배치 upsert
        db.batchUpsert(states)

        logger.info("Synced ${states.size} rooms to DB")
    }

    fun stop() {
        scope.cancel()
    }
}
```

### 4.3 Hybrid 영속성 미들웨어

```kotlin
/**
 * 액션 중요도에 따라 영속성 전략을 분리하는 미들웨어
 *
 * - CriticalAction: 즉시 DB 저장 (트랜잭션)
 * - BufferedAction: Redis 버퍼 후 비동기 동기화
 */
class HybridPersistenceMiddleware<S : State, A : Action>(
    private val redis: RedisClient,
    private val db: Database,
    private val roomId: String,
) : Middleware<S, A> {

    override val name = "HybridPersistence"
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    override fun process(
        getState: () -> S,
        action: A
    ): Flow<A> = flow {
        when (action) {
            is CriticalAction -> {
                // 액션을 먼저 emit하여 미들웨어 체인을 블로킹하지 않음
                emit(action)
                // DB 저장은 emit 이후 비동기로 수행
                db.transaction {
                    persistCriticalAction(action)
                }
            }

            is BufferedAction -> {
                emit(action)
                // Redis 버퍼 (비동기 DB 동기화)
                redis.set("state:$roomId", getState().serialize())
                redis.sadd("dirty:rooms", roomId)
            }

            else -> emit(action)
        }
    }

    private suspend fun persistCriticalAction(action: CriticalAction) {
        when (action) {
            is PurchaseItem -> db.insertPurchase(action)
            is GrantReward -> db.insertReward(action)
            // ...
        }
    }
}

/** 즉시 DB 저장이 필요한 중요 액션 */
interface CriticalAction

/** Redis 버퍼로 충분한 일반 액션 */
interface BufferedAction
```

---

## 5. 수평 확장 시 고려사항

### 5.1 Sticky Session (Room 기반 라우팅)

같은 Room의 클라이언트를 같은 서버로 라우팅하면 Redis Pub/Sub 부하 감소:

```nginx
# nginx.conf
upstream store_servers {
    # roomId 기반 consistent hashing
    hash $arg_roomId consistent;

    server store1:8080;
    server store2:8080;
    server store3:8080;
}

server {
    location /ws {
        proxy_pass http://store_servers;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

**WebSocket 연결 URL 예시:**
```
wss://game.example.com/ws?roomId=room-123
```

### 5.2 Redis Pub/Sub로 서버 간 액션 전파

같은 Room의 플레이어가 다른 서버에 연결된 경우:

```kotlin
/**
 * 서버 간 액션 브로드캐스트를 위한 Redis Pub/Sub 래퍼
 */
class RedisActionBroadcaster<A : Action>(
    private val redis: RedisClient,
    private val codec: ActionCodec<A>,
) {
    /**
     * 다른 서버들에게 액션 전파
     */
    suspend fun broadcast(roomId: String, action: A) {
        val message = codec.encode(action)
        redis.publish("room:$roomId:actions", message)
    }

    /**
     * 다른 서버에서 오는 액션 구독
     */
    fun subscribe(roomId: String): Flow<A> =
        redis.subscribe("room:$roomId:actions")
            .map { codec.decode(it) }
}

/**
 * 서버 간 동기화를 처리하는 미들웨어
 */
class ClusterSyncMiddleware<S : State, A : Action>(
    private val broadcaster: RedisActionBroadcaster<A>,
    private val roomId: String,
    private val serverId: String,
) : Middleware<S, A> {

    override val name = "ClusterSync"
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    override fun process(
        getState: () -> S,
        action: A
    ): Flow<A> = flow {
        emit(action)

        // ClientSharedAction을 다른 서버에도 전파 (fire-and-forget)
        // broadcast 실패 시에도 로컬 액션 처리는 영향받지 않음
        // 다른 서버와의 동기화는 eventual consistency로 보장
        if (action is ClientSharedAction) {
            broadcaster.broadcast(roomId, action)
        }
    }
}
```

### 5.3 Room 상태 마이그레이션

서버 증설/감소 시 Room을 다른 서버로 이동:

```kotlin
/**
 * Redis에서 Room 상태를 복원
 * - 서버 재시작 시
 * - Room 마이그레이션 시
 */
suspend fun <S : State> loadRoomState(
    redis: RedisClient,
    roomId: String,
    deserialize: (String) -> S,
    default: () -> S,
): S {
    val cached = redis.get("state:$roomId")
    return if (cached != null) {
        deserialize(cached)
    } else {
        default()
    }
}

/**
 * Room을 다른 서버로 마이그레이션
 */
suspend fun migrateRoom(
    roomId: String,
    fromServer: StoreServer,
    toServer: StoreServer,
    redis: RedisClient,
) {
    // 1. 현재 서버에서 Room 상태 저장
    val state = fromServer.getRoomState(roomId)
    redis.set("state:$roomId", state.serialize())

    // 2. 현재 서버에서 Room 종료
    fromServer.closeRoom(roomId)

    // 3. 새 서버에서 Room 복원
    toServer.createRoom(roomId)  // loadRoomState() 내부 호출

    // 4. 클라이언트들에게 재연결 요청
    // (WebSocket close with specific code)
}
```

---

## 6. Redis 설정 권장사항

### 6.1 데이터 유실 방지

```
# redis.conf

# AOF 활성화 (Append Only File)
appendonly yes

# 매초 fsync (성능과 안전성 균형)
appendfsync everysec

# 또는 모든 쓰기마다 fsync (최대 안전성, 성능 희생)
# appendfsync always

# RDB 스냅샷 (백업용)
save 900 1      # 15분마다 1개 이상 변경 시
save 300 10     # 5분마다 10개 이상 변경 시
save 60 10000   # 1분마다 10000개 이상 변경 시
```

### 6.2 메모리 관리

```
# redis.conf

# 최대 메모리
maxmemory 2gb

# 메모리 초과 시 정책 (TTL 있는 키 우선 삭제)
maxmemory-policy volatile-lru
```

---

## 7. 적용 시나리오별 권장 설정

| 시나리오 | 영속성 전략 | 동기화 주기 | Redis 설정 |
|---------|-----------|-----------|-----------|
| 실시간 게임 (위치, 상태) | Redis 버퍼 | 1-5초 | appendfsync everysec |
| 채팅 메시지 | Redis 버퍼 | 1초 | appendfsync everysec |
| 결제/거래 | 직접 DB | 즉시 | - |
| 랭킹/리더보드 | Redis 버퍼 | 10-30초 | appendfsync everysec |
| 사용자 설정 | Redis 버퍼 | 10초 | appendfsync everysec |

---

## 8. 체크리스트

### Phase 1 (단일 서버) 준비사항

- [ ] Redis 컨테이너/프로세스 설정
- [ ] `PersistenceMiddleware` 구현
- [ ] `DbSyncWorker` 구현
- [ ] Redis AOF 활성화
- [ ] 상태 직렬화/역직렬화 구현

### Phase 2 (수평 확장) 준비사항

- [ ] Nginx sticky session 설정 (consistent hash)
- [ ] `RedisActionBroadcaster` 구현
- [ ] `ClusterSyncMiddleware` 구현
- [ ] Room 상태 복원 로직 구현
- [ ] 서버 헬스체크 엔드포인트

### Phase 3 (완전 분리) 준비사항

- [ ] Redis Cluster 구성
- [ ] DB 읽기 복제본 분리
- [ ] Room 마이그레이션 로직
- [ ] 모니터링/알림 설정

---

## 9. 참고

- [Redis Persistence](https://redis.io/docs/management/persistence/)
- [Write-Behind Caching Pattern](https://docs.aws.amazon.com/whitepapers/latest/database-caching-strategies-using-redis/write-behind-caching.html)
- [Consistent Hashing in Nginx](https://nginx.org/en/docs/http/ngx_http_upstream_module.html#hash)

---

*문서 작성일: 2026-02-03*
