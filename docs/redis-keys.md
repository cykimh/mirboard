# Mirboard Redis 키 설계 (Phase 1)

## 원칙

- **모든 키는 `EXPIRE` 한다.** 좀비 방 누적 방지 + 메모리 한도 보호.
- **민감/마스터 상태(`*:state`, `*:hand:*`)는 서버 코드에서만 접근**한다. 클라이언트 측
  Redis 직접 노출 금지(애초에 노출 경로가 없도록 인프라 분리).
- **Set과 Read는 분리**한다. 트랜잭션/원자성이 필요한 경로는 Lua 스크립트.

## 키 카탈로그

| 키 | 타입 | TTL | 필드 / 값 | 비고 |
| --- | --- | --- | --- | --- |
| `room:{roomId}` | HASH | 6h | `hostId`, `name`, `gameType`, `status`, `capacity`, `createdAt`, `updatedAt`, `teamPolicy`, `fillWithBots`, `targetScore`, `turnSeconds`, `stake` | 메타. `stake`(D-81)=판돈(가상 칩, 0=내기없음), 생성 시 고정·불변. `capacity`(D-99)=방 인원, 생성 시 게임의 `minPlayers()..maxPlayers()` 안에서 확정·불변 |
| `room:{roomId}:players` | LIST | 6h | 입장 순서대로 `userId` push (`LLEN` ≤ `capacity`) | 자리 = index |
| `rooms:open` | ZSET | — | member=roomId, score=createdAt | 대기방 목록 표시 (status==WAITING 만 포함) |
| `room:{roomId}:state` | STRING(JSON) | 6h | 마스터 `TichuState` 전체 (덱 잔여, 손패 포함) | 직렬화 책임은 GameEngine |
| `room:{roomId}:hand:{userId}` | STRING(JSON) | 6h | 해당 유저 손패 캐시 | resync 빠른 응답 용 (state로부터 파생 가능) |
| `match:{roomId}:state` | STRING(JSON) | 6h | 티츄 `TichuMatchState` — 누적 점수/라운드 번호/라운드별 RoundScore. 스컬킹 `SkullKingMatchState` — `roundNumber`·`startSeat`·`cumulativeScores`(좌석→누적)·`desertedSeats`·`completedRounds`(`[{roundNumber, scores:{seat:{bid,won,base,bonus}}}]`, 정산 끝난 라운드만, D-120)·`roundsPlayed`(완주 라운드 수, 매치가 끝날 때 확정 — 진행 중·구 JSON 은 `null`, D-122) | Phase 5c 추가, 라운드 전환 시 유지. 방당 게임 하나라 키 공유. 스컬킹은 필드 부재 구 JSON 을 빈 값으로 읽고 모르는 필드는 무시(`@JsonIgnoreProperties(ignoreUnknown)`, D-120 — 다음 필드 추가부터 롤백 안전) |
| `room:{roomId}:ready` | SET | 6h | 대기실 준비 완료 `userId` (봇은 join 시 자동 추가) | Phase 16(#2). 전원 ready+정원 → IN_GAME. D-74: 빈 방 leave 시 `room_leave.lua` 가 함께 삭제 |
| `room:{roomId}:chips` | HASH | 6h | 방 단위 테이블 칩 `userId`→칩(D-82) | 내기 방만. 게임 시작 시 전원 동일 칩 init(리매치 시 유지), 매치 종료마다 `RoomChipService` 정산. 계정 아님 — 방 소멸 시 TTL 정리 |
| `room:{roomId}:spectators` | SET | 6h | 관전자 `userId` | D-75: 빈 방 destroy(`room_leave.lua`) 및 `room_delete.lua` 가 함께 삭제 — 고아 키 방지 |
| `room:{roomId}:seq` | STRING(INTEGER) | 6h | 이벤트 단조 카운터 | `room_seq_next.lua`(INCR+EXPIRE)로만 변경. TTL 은 **이벤트 발행마다 갱신**(슬라이딩) — 활동 중인 방에서 만료돼 1부터 다시 시작하면 클라 seq gap 판정이 깨진다 |
| `room:{roomId}:lock` | STRING | 2s | 액션 직렬화 락 | `SET key NX EX 2` |
| `presence:room:{roomId}` | HASH | 6h | `userId` → 해당 방을 보고 있는 **세션 수** | D-96(D-111 보정). `RoomPresence`. 방 토픽 SUBSCRIBE 시 `presence_join.lua` 로 **세션당 1회만** `HINCRBY +1`, DISCONNECT 시 `presence_leave.lua` 로 −1(0 이면 `HDEL`, 빈 HASH 면 키 자체 `DEL`). **boolean 이 아니라 카운터** — 탭 두 개 중 하나만 닫아도 접속 중이어야 탈주 오판이 없다. 탈주 유예 만료 시 "재접속했는가"(`hasLiveSession`) 판정의 근거 |
| `presence:session:{sessionId}` | STRING | 6h | `"{userId}:{roomId}"` | D-96(D-111 보정). `RoomPresence`. 역할 둘: ① DISCONNECT 이벤트는 sessionId 만 주므로 역방향 조회, ② **"이 세션을 이미 셌는가" 표식** — `SET NX` 성공 시에만 카운터를 올려 한 세션의 구독 여러 개가 중복 계수되지 않게 한다. leave 시 `DEL` |
| `deadlines:{kind}` | ZSET | 12h | member=페이로드, score=만료 `epochMillis` | D-96. `DeadlineQueue`. `kind`=`turn`(member `{roomId}#{generation}`, `TurnTimeoutScheduler`) · `desertion`(member `{roomId}:{userId}`, `DesertionGraceScheduler`). 모든 인스턴스가 폴링(`mirboard.scheduling.poll-interval-millis`, 기본 250ms)하고 만료분 pop 은 `deadline_poll.lua` 로 원자화 — 한 항목은 정확히 한 인스턴스에만 간다. 같은 member 재등록 = score 갱신(= 기존 타이머 취소+재등록). `schedule()` 마다 EXPIRE 갱신 |
| `login:fail:{username}` | STRING(INTEGER) | 윈도(기본 15m) | 로그인 연속 실패 횟수 | D-84. `INCR`+첫 실패 시 EXPIRE. 임계 초과 시 lock 설정, 성공 시 DEL |
| `lock:login:{username}` | STRING | 잠금(기본 15m) | 잠금 마커 | D-84. 존재 시 423 ACCOUNT_LOCKED. users 스키마 비침범(휘발) |
| `ratelimit:{bucket}:{subject}` | STRING(INTEGER) | 윈도(TTL) | 버킷별 요청 카운터 | D-90(D-84 확장). `bucket`=`auth`·`guest`(D-117, 24h)·`api-default`·`room-create`·`expensive-write`·`game-action`·`chat`·`reaction`·`stomp-default`. `subject`=인증 시 `u:{userId}`, 아니면 `ip:{ip}`(NAT 오탐 회피). **D-117: `/api/auth/**`(`auth`·`guest`)는 Bearer 를 실어도 항상 `ip:` 키**. IP 는 신뢰 헤더 `mirboard.ratelimit.client-ip-header`(운영 `Fly-Client-IP`, 없으면 remoteAddr)에서 읽고 IPv6 는 `x:x:x:x::/64` 로 묶는다(예: `ratelimit:guest:ip:2001:db8:1:2::/64`). `bucket` 은 원본 URI 가 아니라 MVC·Security 가 매칭하는 정규화 경로(디코딩·contextPath/`X-Forwarded-Prefix` 제외)로 고른다 — 인코딩 변형으로 버킷을 갈아타지 못하게(D-117 보정). Lua 원자 고정 윈도(`INCR`+`EXPIRE`). HTTP 초과=429(`Retry-After`=윈도 초), STOMP 초과=드롭(액션만 본인 큐 `ERROR(RATE_LIMITED)`). 클라 IP 는 휘발 카운터 키(영속 로그 아님) |
| `guest:issued:{yyyy-MM-dd}` | STRING(INTEGER) | 48h | 그날(UTC) 발급한 게스트 수 | D-117. `GuestAccountService` 가 생성마다 `guest_daily_issue.lua` 로 `INCR`+첫 발급 `EXPIRE 48h` 를 한 번에(원자 — 다중 인스턴스에서도 상한 초과 발급 없고, TTL 없는 날짜 키가 남지 않음). `mirboard.guest.daily-cap`(기본 200) 초과면 503 `GUEST_UNAVAILABLE`. Redis 장애면 판정 불가로 **fail-closed**(레이트리밋의 fail-open 과 반대 — 전역 상한은 비용 상한이라). 70% 도달 WARN, 첫 거절 ERROR(Sentry) |
| `guest:sweep:lock` | STRING | 10m | 게스트 정리 스로틀 락 | D-117. `SET NX EX 600` 을 잡은 인스턴스만 `GuestAccountSweeper.sweepOnce()` 실행 — 생성 경로에서 10분에 1회 |
| `guest:sweep:cursor` | STRING(INTEGER) | 7d | 정리 커서(마지막으로 본 users.id) | D-117. 배치가 가득 차면 마지막 id 로 전진, 덜 차면 0 으로 되돌림 — FK 위반으로 못 지우는 행(독 행)이 배치 크기 이상 쌓여도 정리가 같은 자리에서 멈추지 않는다 |
| `chatlog:lobby` / `chatlog:room:{roomId}` | LIST(JSON) | 2h | 최근 채팅 100개 `{eventId,userId,username,message,ts}` | D-93. **신고 시 서버가 원문·작성자를 확정하기 위한 근거** — 클라가 본문을 제출하면 무고가 가능하므로(Server-Authoritative). 상시 채팅 로그 영속화가 아니다: 여기서 휘발되고 **신고된 것만** `chat_reports`(V9)로 승격. `message` 는 D-86 마스킹 적용 후 본문 |
| `suspend:user:{userId}` | STRING | 정지 기간(TTL) | 어드민 유저 정지 마커 | D-86. 존재 시 로그인/CONNECT 차단(403 ACCOUNT_SUSPENDED). users 스키마 비침범(휘발) |

> **D-96**: 세션→방 매핑은 Redis `presence:*` 다. D-75 가 도입했던 in-memory
> `WsSessionRegistry` 는 **삭제됐고**(클래스 없음), 같은 역할을 `RoomPresence` 가
> Redis 로 수행한다. 단일 인스턴스 전제(D-03)는 D-96 이 번복 — 인스턴스 A 에 붙은
> 재접속을 B 가 못 봐서 **재접속을 탈주로 오판**하던 것이 전환 이유다.
> 설계 단계의 placeholder 였던 `session:{userId}`·`presence:lobby` 행은 **구현되지
> 않은 채 남아 있어 삭제**했다(코드 전수 검색 0건). 세션→방은 위 `presence:*` 가,
> 방별 접속자 조회는 `RoomPresence.viewers(roomId)` 가 대신한다.

> **왜 등록이 멱등해야 하나 (D-111)**: `WsSessionLifecycleListener.onSubscribe` 는
> `^/topic/room/([^/]+)(?:/.*)?$` 에 매칭되는 **구독마다** 호출되는데(클라 `useStompRoom`
> 은 한 세션에서 `/topic/room/{id}`·`/chat`·`/reaction` **3개**를 구독한다), `onDisconnect`
> 는 세션당 **한 번만** 호출된다. 등록이 세션당 1회로 접히지 않으면 `+3 / −1` 로 잔여
> 카운터가 남아 `hasLiveSession` 이 끊긴 뒤에도 참이 되고, 탈주 유예가 만료돼도
> "재접속함"으로 판정돼 **탈주가 확정되지 않는다**. 리스너가 아니라 `RoomPresence` 쪽에서
> 접는 이유는, 구독 목적지가 클라가 보내는 값이라 매칭 규칙을 신뢰 기준으로 삼을 수 없기
> 때문이다(Server-Authoritative).

> `rooms:open` 은 TTL이 없는 대신, 방이 `IN_GAME`/`FINISHED` 가 되거나 삭제되면
> ZREM 으로 동기 제거된다.

## 원자성 보증 (Lua 스크립트)

### `room_create.lua`
입력: `KEYS = [room:{id}, room:{id}:players, rooms:open]`,
`ARGV = [roomId, hostId, name, gameType, capacity, createdAt, teamPolicy,
fillWithBots, targetScore, turnSeconds, stake]`.

방 메타 HASH + 호스트 `RPUSH` + `rooms:open` ZADD 를 한 덩어리로 생성(양쪽 EXPIRE 6h).
UUID 라 충돌은 없어야 하지만 기존 키를 덮지 않도록 `EXISTS` 시 `-10`(ROOM_ID_COLLISION).
성공 `1`.

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

### `room_finish.lua`
입력: `KEYS = [room:{id}, rooms:open]`, `ARGV = [roomId, now]`.
`status=FINISHED` + `ZREM rooms:open` + 방 메타 TTL 을 **600s 로 단축**(결과 화면이
머무를 시간만 남기고 자연 만료). 방 없으면 `-1`, 성공 `1`. state/hand 정리는 호출자
(게임별 cleanup) 몫.

### `presence_join.lua` *(D-111)*
입력: `KEYS = [presence:room:{roomId}, presence:session:{sessionId}]`,
`ARGV = [userId, roomId, ttlSeconds]`.

세션 키를 `SET NX` 로 잡아 **성공했을 때만** `HINCRBY +1`, 실패(= 이미 등록된 세션)면
TTL 만 갱신. 반환은 이 호출로 카운터가 올라갔으면 `1`, 건너뛰었으면 `0`.
**"이 세션을 이미 셌는가" 판정과 증가가 한 원자 단위**여야 하는 이유: 한 세션의 SUBSCRIBE
프레임 3개가 동시에 처리되면 검사-후-증가가 갈라져 카운터가 2~3까지 올라가고, 그만큼
DISCONNECT 후에도 잔여가 남아 **탈주가 확정되지 않는다**.
(세션이 다른 방으로 재사용된 경우는 `RoomPresence.join` 이 옛 방 `leave` 를 먼저 태운다 —
옛 방 HASH 는 `KEYS` 밖이라 스크립트가 건드릴 수 없다.)

### `presence_leave.lua` *(D-96)*
입력: `KEYS[1] = presence:room:{roomId}`, `ARGV = [userId, ttlSeconds]`.

`HINCRBY -1` 후 0 이하면 `HDEL` 로 필드를 지워 "접속 없음"으로 만들고, HASH 가 비면
키 자체를 `DEL`(아니면 EXPIRE 갱신). 반환값은 남은 세션 수.
**감소·삭제·TTL 갱신이 한 원자 단위**여야 하는 이유: 탭 여러 개가 동시에 닫힐 때
읽고-쓰기가 갈라지면 카운터가 음수로 새거나 살아 있는 세션이 지워져 **재접속을 탈주로
오판**한다.

### `deadline_poll.lua` *(D-96)*
입력: `KEYS[1] = deadlines:{kind}`, `ARGV = [nowMillis, maxCount]`.

`ZRANGEBYSCORE (-inf, now] LIMIT 0 maxCount` + 가져온 것만 `ZREM` 을 한 덩어리로 묶는다.
**모든 인스턴스가 같은 ZSET 을 폴링**하므로 pop 이 원자적이지 않으면 두 인스턴스가 같은
타이머를 동시에 발화한다. 원자 pop 이라 한 항목은 정확히 한 인스턴스에만 가고, 인스턴스가
죽어도 ZSET 이 남아 다른 인스턴스가 자동 인계한다(리더 선출 불필요 — 리더 부재라는 장애
모드를 만들지 않으려는 선택). `LIMIT` 은 한 인스턴스가 폭주분을 독점하지 않게 하는 상한.
반환: 만료된 member 배열.

### `rate_limit_fixed_window.lua` *(D-84)*
입력: `KEYS[1] = ratelimit:{bucket}:{subject}`, `ARGV = [limit, windowSeconds]`.
`INCR` 후 카운트가 1(=윈도 첫 요청)일 때만 `EXPIRE` — 두 단계가 갈라지면 TTL 없는
카운터가 영구 잔존해 해당 subject 가 영구 차단된다. 한도 초과 `0`, 허용 `1`.

### `guest_daily_issue.lua` *(D-117)*
입력: `KEYS[1] = guest:issued:{UTC yyyy-MM-dd}`, `ARGV[1] = ttl 초(48h)`.
`INCR` 후 순번이 1(=그날 첫 발급)일 때만 `EXPIRE`, 새 순번을 반환한다(상한 비교·경보는
`GuestAccountService`). `rate_limit_fixed_window.lua` 와 같은 이유로 묶는다 — `INCR` 뒤
`EXPIRE` 가 실패하거나 프로세스가 죽으면 TTL 없는 날짜 키가 영구히 남고, 이후 요청은 순번이
2 이상이라 다시 걸지 않는다.

### `room_seq_next.lua`
입력: `KEYS[1]=room:{id}:seq`, `ARGV[1]=ttl 초(6h)`. `INCR` 후 `EXPIRE` 하고 새 seq 를
반환한다.

두 명령을 굳이 원자로 묶는 이유는 두 가지다. (1) 발행 경로는 이벤트마다 도는
핫패스라 왕복을 늘리고 싶지 않다. (2) 나눠 보내면 그 사이에 죽었을 때 TTL 없는 고아
카운터가 남는다 — 실제로 순수 `INCR` 이던 시절 방 하나당 카운터 하나가 영구히
적립됐다(문서는 처음부터 6h 였고 코드만 어긋나 있었다). 묶어두면 "카운터가 있으면
TTL 도 있다" 가 항상 참이다.

**매번** EXPIRE 하는 것도 의도다. 첫 발행에만 걸면 6h 를 넘기는 방에서 카운터가 매치
도중 만료돼 `INCR` 이 1부터 다시 시작하고, 클라의 seq gap 판정이 깨진다. 정리 Lua 로
지우지 않는 이유도 같다(위 `room_leave.lua` 3번 참고) — 방이 살아있는 동안 사라지면
안 되는 키라, 삭제가 아니라 TTL 로만 사그라들게 둔다.

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
- `presence:session:{sessionId}` 는 DISCONNECT 시 즉시 `DEL`,
  `presence:room:{roomId}` 는 `presence_leave.lua` 가 카운터를 내리고 0 이면 필드를 지운다
  (마지막 세션이 나가면 키까지 `DEL`). 탈주 유예는 이 프레즌스와 별개로
  `deadlines:desertion` 에 걸리며 기본 120s(D-79, `mirboard.desertion.grace-seconds`).
- `RoomPresence.clearRoom` 은 현재 **호출부가 없다** — 정상 경로에서는 마지막 DISCONNECT 가
  카운터를 0 으로 만들며 키까지 지우므로(D-111 이후) 고아는 남지 않는다. 다만 세션이 끊김
  없이 방만 사라지는 경로에서는 TTL(6h)까지 잔류할 수 있다.

## 멀티 인스턴스 (D-96 이후 — 더 이상 범위 밖 아님)

- 단일 인스턴스 전제(D-03)는 **D-96 에서 번복**됐다. 인스턴스에 묶여 있던 세 가지
  (`WsSessionRegistry` · `TurnTimeoutScheduler` · `DesertionGraceScheduler`)가 위
  `presence:*` / `deadlines:{kind}` 로 옮겨졌고, 2-인스턴스 통합 테스트
  (`TwoInstanceHandoffIT`)가 데드라인 인계·중복 실행 0·교차 프레즌스 조회를 검증한다.
- STOMP 브로커는 여전히 Spring 내장 `SimpleBroker` 지만, fan-out 은 `MessageGateway`
  추상화 뒤에 있다(Phase 6D). `MIRBOARD_MESSAGING_GATEWAY=redis` 로 켜면 STOMP
  broadcast(`stomp:routes` 채널, `StompMessageRelay`)와 도메인 이벤트(`DomainEventBus`)가
  Redis Pub/Sub 위로 흐르므로 **sticky session 없이** 작동한다. 기본값은 `in-memory`.
