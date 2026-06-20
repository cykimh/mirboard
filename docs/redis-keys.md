# Mirboard Redis 키 설계 (Phase 1)

## 원칙

- **모든 키는 `EXPIRE` 한다.** 좀비 방 누적 방지 + 메모리 한도 보호.
- **민감/마스터 상태(`*:state`, `*:hand:*`)는 서버 코드에서만 접근**한다. 클라이언트 측
  Redis 직접 노출 금지(애초에 노출 경로가 없도록 인프라 분리).
- **Set과 Read는 분리**한다. 트랜잭션/원자성이 필요한 경로는 Lua 스크립트.

## 키 카탈로그

| 키 | 타입 | TTL | 필드 / 값 | 비고 |
| --- | --- | --- | --- | --- |
| `room:{roomId}` | HASH | 6h | `hostId`, `name`, `gameType`, `status`, `capacity`, `createdAt`, `updatedAt`, `teamPolicy`, `fillWithBots`, `targetScore`, `turnSeconds`, `stake` | 메타. `stake`(D-81)=판돈(가상 칩, 0=내기없음), 생성 시 고정·불변 |
| `room:{roomId}:players` | LIST | 6h | 입장 순서대로 `userId` push (capacity ≤ 4) | 자리 = index |
| `rooms:open` | ZSET | — | member=roomId, score=createdAt | 대기방 목록 표시 (status==WAITING 만 포함) |
| `room:{roomId}:state` | STRING(JSON) | 6h | 마스터 `TichuState` 전체 (덱 잔여, 손패 포함) | 직렬화 책임은 GameEngine |
| `room:{roomId}:hand:{userId}` | STRING(JSON) | 6h | 해당 유저 손패 캐시 | resync 빠른 응답 용 (state로부터 파생 가능) |
| `match:{roomId}:state` | STRING(JSON) | 6h | `TichuMatchState` — 누적 점수/라운드 번호/라운드별 RoundScore | Phase 5c 추가, 라운드 전환 시 유지 |
| `room:{roomId}:ready` | SET | 6h | 대기실 준비 완료 `userId` (봇은 join 시 자동 추가) | Phase 16(#2). 전원 ready+정원 → IN_GAME. D-74: 빈 방 leave 시 `room_leave.lua` 가 함께 삭제 |
| `room:{roomId}:chips` | HASH | 6h | 방 단위 테이블 칩 `userId`→칩(D-82) | 내기 방만. 게임 시작 시 전원 동일 칩 init(리매치 시 유지), 매치 종료마다 `RoomChipService` 정산. 계정 아님 — 방 소멸 시 TTL 정리 |
| `room:{roomId}:spectators` | SET | 6h | 관전자 `userId` | D-75: 빈 방 destroy(`room_leave.lua`) 및 `room_delete.lua` 가 함께 삭제 — 고아 키 방지 |
| `room:{roomId}:seq` | STRING(INTEGER) | 6h | 이벤트 단조 카운터 | `INCR` 로만 변경 |
| `room:{roomId}:lock` | STRING | 2s | 액션 직렬화 락 | `SET key NX EX 2` |
| `session:{userId}` | HASH | 30m | `currentRoomId`, `wsSessionId`, `lastSeenAt` | WS CONNECT 시 갱신 |
| `presence:lobby` | SET | — | 로비 접속자 userId | WS DISCONNECT 시 SREM |

> Phase 19(#1, D-75): 세션→방 매핑은 Redis presence 키가 아니라 서버
> in-memory `WsSessionRegistry`(SUBSCRIBE 등록 / DISCONNECT 제거)로 구현.
> 단일 인스턴스 MVP(D-03) 전제 — 다중 인스턴스 전환 시 Redis presence 로
> 교체(범위 밖). `session:{userId}`/`presence:*` 행은 향후 설계용 placeholder.

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
**Phase 16(#2)**: 정원 도달 시 IN_GAME 자동전이 블록을 제거했다. 시작은
`room_ready.lua` 가 전담.

### `room_ready.lua` *(Phase 16 #2)*
입력: `KEYS = [room:{id}, room:{id}:players, room:{id}:ready, rooms:open]`,
`ARGV = [userId, ready('1'|'0'), roomId]`.

처리:
1. 방 없음 → `-1`. status≠WAITING → `-2`. players 에 userId 없음 → `-3`.
2. ready='1' 이면 `SADD ready userId`, '0' 이면 `SREM ready userId` (+EXPIRE).
3. `LLEN players >= capacity` **and** `SCARD ready >= capacity` 이면
   `HSET room status IN_GAME` + `ZREM rooms:open` → `1`(started), 아니면 `0`.

단일 원자 트랜잭션 — 다수 플레이어가 동시에 마지막 ready 를 눌러도 `1`(start)
은 정확히 한 번만 반환되어 GameStartingEvent 중복 발행 0건.

### `room_leave.lua`
입력: `KEYS = [room, players, rooms:open, room:{id}:ready,
room:{id}:spectators]`, `ARGV = [userId, roomId]`.

처리:
1. `LREM players 0 userId` (없으면 `-2` NOT_IN_ROOM).
2. `players` 빈 리스트면 `DEL room players ready spectators` 및
   `ZREM rooms:open` → `0`(방 파괴). D-74: ready, D-75: spectators 도
   함께 삭제(고아 키 방지).
3. 호스트가 떠났다면 `LINDEX players 0` 으로 새 호스트 지정 후 `HSET room hostId`.
   (state/hand/seq 키는 게임별 cleanup·TTL 로 소멸 — leave 스크립트 비관여.)
4. 남은 인원 수(또는 `0`) 반환.

### `room_delete.lua` *(Phase 19 #1, D-75)*
입력: `KEYS = [room, players, room:{id}:ready, room:{id}:spectators,
rooms:open]`, `ARGV = [roomId]`.
멤버십 검사 없이 방을 무조건 원자 소멸 — `DEL room players ready
spectators` + `ZREM rooms:open`. "플레이어 0 && 관전자 0"(관전자만 남았다가
마지막 관전자가 나간 경우)을 `RoomService.destroyIfEmpty` 가 정리할 때
호출. 방 존재 시 `1`, 없으면 `0` 반환.

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
