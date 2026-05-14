# Mirboard Redis 키 설계 (Phase 1)

## 원칙

- **모든 키는 `EXPIRE` 한다.** 좀비 방 누적 방지 + 메모리 한도 보호.
- **민감/마스터 상태(`*:state`, `*:hand:*`)는 서버 코드에서만 접근**한다. 클라이언트 측
  Redis 직접 노출 금지(애초에 노출 경로가 없도록 인프라 분리).
- **Set과 Read는 분리**한다. 트랜잭션/원자성이 필요한 경로는 Lua 스크립트.

## 키 카탈로그

| 키 | 타입 | TTL | 필드 / 값 | 비고 |
| --- | --- | --- | --- | --- |
| `room:{roomId}` | HASH | 6h | `hostId`, `name`, `gameType`, `status`, `capacity`, `createdAt`, `seq` | 메타 |
| `room:{roomId}:players` | LIST | 6h | 입장 순서대로 `userId` push (capacity ≤ 4) | 자리 = index |
| `rooms:open` | ZSET | — | member=roomId, score=createdAt | 대기방 목록 표시 (status==WAITING 만 포함) |
| `room:{roomId}:state` | STRING(JSON) | 6h | 마스터 `TichuState` 전체 (덱 잔여, 손패 포함) | 직렬화 책임은 GameEngine |
| `room:{roomId}:hand:{userId}` | STRING(JSON) | 6h | 해당 유저 손패 캐시 | resync 빠른 응답 용 (state로부터 파생 가능) |
| `match:{roomId}:state` | STRING(JSON) | 6h | `TichuMatchState` — 누적 점수/라운드 번호/라운드별 RoundScore | Phase 5c 추가, 라운드 전환 시 유지 |
| `room:{roomId}:seq` | STRING(INTEGER) | 6h | 이벤트 단조 카운터 | `INCR` 로만 변경 |
| `room:{roomId}:lock` | STRING | 2s | 액션 직렬화 락 | `SET key NX EX 2` |
| `session:{userId}` | HASH | 30m | `currentRoomId`, `wsSessionId`, `lastSeenAt` | WS CONNECT 시 갱신 |
| `presence:lobby` | SET | — | 로비 접속자 userId | WS DISCONNECT 시 SREM |

> `rooms:open` 은 TTL이 없는 대신, 방이 `IN_GAME`/`FINISHED` 가 되거나 삭제되면
> ZREM 으로 동기 제거된다.

## 원자성 보증 (Lua 스크립트)

### `room_join.lua`
입력: `KEYS[1]=room:{id}`, `KEYS[2]=room:{id}:players`, `ARGV[1]=userId`,
`ARGV[2]=now`.

처리:
1. `HGET room status` 검사 → `WAITING` 아니면 `"NOT_WAITING"` 반환.
2. `HGET room capacity` 와 `LLEN players` 비교 → 같으면 `"FULL"`.
3. `LRANGE players` 에 userId 있으면 `"ALREADY_IN"`.
4. `RPUSH players userId`, `HSET room ... lastUpdatedAt=now` → `"OK"`.

모든 단계가 단일 원자 트랜잭션. 4명이 동시 입장해도 capacity 위반 0건 보장.

### `room_leave.lua`
입력: `KEYS = [room, players]`, `ARGV = [userId, now]`.

처리:
1. `LREM players 0 userId` (없으면 `"NOT_IN"`).
2. `players` 빈 리스트면 `DEL room players state hand:* seq` 및 `ZREM rooms:open`
   → `"EMPTY"`.
3. 호스트가 떠났다면 `LINDEX players 0` 으로 새 호스트 지정 후 `HSET room hostId`.
4. `"OK"` 반환.

### `room_action_seq.lua` (선택)
액션 처리 직후 `INCR seq` + 이벤트 페이로드를 Pub/Sub 으로 동시 발행. 단일 인스턴스
배포에서는 굳이 필요 없고 Spring 측 `convertAndSend` 로 충분.

## 직렬화 포맷

- JSON (`MappingJackson2HttpMessageConverter` 와 같은 ObjectMapper 인스턴스 사용).
- 손패는 `cardRef` 배열로 직렬화 (`{suit,rank}` 또는 `{special}`).
- `TichuState` 는 internal-only 타입이며 클라에 절대 노출되지 않는다.

## 키 청소

- 게임 종료(`GAME_ENDED` 처리) 시 `room:{id}:state`, `room:{id}:hand:*` 즉시 DEL.
- 방 메타(`room:{id}`, `players`) 는 잔류 인원이 잠시 결과 화면에 머무를 수 있도록
  TTL 10분으로 단축한 뒤 자연 만료.
- `session:{userId}` 는 WS DISCONNECT 후 grace 30s 동안 유지 → 재접속 시 갱신.

## 멀티 인스턴스 확장 시 고려 (현재 범위 밖)

- 본 MVP는 **단일 인스턴스 배포** 가정. STOMP 메시지 브로커는 Spring 내장
  `SimpleBroker` 사용.
- 추후 스케일 아웃 시 Redis Pub/Sub 또는 외부 메시지 브로커(RabbitMQ STOMP relay)로
  교체할 수 있도록, 컨트롤러는 `EventPublisher` 추상화 뒤에 둔다.
