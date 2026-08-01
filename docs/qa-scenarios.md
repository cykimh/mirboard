# 수동 검증 시나리오

REST/STOMP 계약의 **정본**은 [api.md](api.md) · [stomp-protocol.md](stomp-protocol.md) 이고,
이 문서는 손으로 훑을 때 쓰는 대본입니다. 자동 검증은 `./scripts/check.sh server` 가 합니다.

> 아래 절 제목의 Phase 번호는 작성 당시 진행 단계이고, 지금은 기능 구분으로 읽으면 됩니다.
> 티츄 특수 카드 시나리오(마작 소원 / 드래곤 양도 / 피닉스 단독)는 해당 룰의 **유일한
> 수동 검증 대본**이므로 유지합니다.

## Phase 2b — 인증 (Auth) 동작 확인

회원가입 → 로그인 → 본인 정보 조회:

```bash
# 1) 회원가입
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice_01","password":"validpass1"}'
# → {"userId":1,"username":"alice_01"}

# 2) 로그인 (JWT 획득)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice_01","password":"validpass1"}' | jq -r .accessToken)

# 3) 본인 정보 조회
curl -s http://localhost:8080/api/me -H "Authorization: Bearer $TOKEN"
# → {"userId":1,"username":"alice_01","winCount":0,"loseCount":0}
```

예외 케이스:
- 같은 username 재등록 → `409 USERNAME_TAKEN`
- 비밀번호 오류 → `401 BAD_CREDENTIALS`
- 토큰 없는 `/api/me` → `401 UNAUTHORIZED`
- username 규칙 위반 (`^[A-Za-z0-9_]{3,20}$`) → `400 INVALID_INPUT`

## Phase 2c — 게임 카탈로그

```bash
# 카탈로그 조회 (인증 필요)
curl -s http://localhost:8080/api/games -H "Authorization: Bearer $TOKEN"
# → {"games":[{"id":"TICHU","displayName":"티츄",
#              "shortDescription":"4인 파트너 카드 게임. 56장 덱과 4장의 특수 카드...",
#              "minPlayers":4,"maxPlayers":4,"status":"AVAILABLE"},
#             {"id":"SKULL_KING","displayName":"스컬킹",
#              "shortDescription":"2~8인 트릭테이킹. 매 라운드 자기 승수를 예측하고...",
#              "minPlayers":2,"maxPlayers":8,"status":"AVAILABLE"}]}
# 순서는 보장하지 않는다 — GameRegistry 가 GameDefinition Bean 을 모아 만든다.

# 단일 게임
curl -s http://localhost:8080/api/games/TICHU -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/api/games/SKULL_KING -H "Authorization: Bearer $TOKEN"

# 미등록 게임 → 404 GAME_NOT_AVAILABLE
curl -s http://localhost:8080/api/games/UNKNOWN -H "Authorization: Bearer $TOKEN"
```

새 게임 추가 절차: `domain.game.{newgame}` 패키지에 `GameDefinition` 구현체를
`@Component` 로 만들면 카탈로그/단일조회 자동 노출. 로비/허브/REST 코드 수정 불필요.

## Phase 2d — 방(Room)

```bash
# 방 생성 (자동으로 본인이 host로 입장)
ROOM=$(curl -s -X POST http://localhost:8080/api/rooms \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"친구들 한 판","gameType":"TICHU"}' | jq -r .roomId)

# 방 목록 (WAITING 만, gameType 필터 가능)
curl -s "http://localhost:8080/api/rooms?gameType=TICHU" \
  -H "Authorization: Bearer $TOKEN"

# 입장 / 단일 조회 / 퇴장
curl -s -X POST http://localhost:8080/api/rooms/$ROOM/join  -H "Authorization: Bearer $TOKEN"
curl -s        http://localhost:8080/api/rooms/$ROOM        -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost:8080/api/rooms/$ROOM/leave -H "Authorization: Bearer $TOKEN"
```

예외 케이스:
- 방 만석 입장 → `409 ROOM_FULL`
- 이미 입장한 방 재입장 → `409 ALREADY_IN_ROOM`
- IN_GAME 으로 전환된 방 입장 → `409 GAME_ALREADY_STARTED`
- 등록 안 된 gameType → `404 GAME_NOT_AVAILABLE`
- 모르는 roomId → `404 ROOM_NOT_FOUND`

**원자성 보증**: `room_create.lua` / `room_join.lua` / `room_leave.lua` 가 Redis
단일 스레드 위에서 capacity 체크 → players push → 메타 갱신을 한 번에 처리.
`RoomServiceConcurrencyIT` 가 9 스레드 동시 입장으로 capacity=4 위반 0건을 검증.

## Phase 2e — WebSocket / STOMP

- 엔드포인트: `ws://<host>/ws` (raw) + SockJS fallback.
- CONNECT 시 `Authorization: Bearer <JWT>` 헤더 필수 — 없거나 위조면 거절.
- 채널:
  - `/topic/lobby/chat` — 로비 채팅 (서버 발행)
  - `/topic/lobby/rooms` — 방 변경 알림 (`ROOM_UPDATED` / `ROOM_DESTROYED`)
  - 클라 발행: `/app/lobby/chat` `{ "message": "..." }`
- 메시지 envelope: `{ "eventId", "type", "ts", "payload" }`. 상세는
  [`docs/stomp-protocol.md`](stomp-protocol.md).

브라우저에서 빠른 검증 (`@stomp/stompjs` 기준):
```js
import { Client } from '@stomp/stompjs';
const client = new Client({
  brokerURL: 'ws://localhost:8080/ws',
  connectHeaders: { Authorization: `Bearer ${token}` },
  onConnect: () => {
    client.subscribe('/topic/lobby/chat', (m) => console.log(JSON.parse(m.body)));
    client.publish({ destination: '/app/lobby/chat', body: JSON.stringify({ message: 'hi' }) });
  },
});
client.activate();
```

## 전체 시연 (End-to-End)

```bash
# 1) 인프라
docker compose up -d postgres redis

# 2) 백엔드 (Spring Boot 4.0.1, Java 25)
./gradlew :server:bootRun
# → http://localhost:8080

# 3) 프론트엔드 (Vite, 새 터미널)
npm --prefix client install   # 처음 한 번
npm --prefix client run dev
# → http://localhost:5173 — Vite 가 /api 와 /ws 를 8080 으로 proxy
```

브라우저 4개(또는 incognito 창 4개) 로:
1. `/register` 에서 4명의 사용자 가입 (예: `p1` ~ `p4`).
2. 각자 로그인 → **미르보드카페(`/games`)** 진입. 게임 카탈로그·방 목록·랭킹·로비 채팅이
   한 화면에 있다 (게임별 로비 페이지 `/games/:id/lobby` 는 Phase 16(#1)에서 폐지).
3. p1 이 "방 만들기" → 모달에서 티츄 선택 → 생성. p2, p3, p4 가 목록에서 입장.
4. **전원이 "준비"** 를 누르면 `room_ready.lua` 가 WAITING→IN_GAME 을 원자 전이시키고
   `GameStartingEvent` → `TichuRoundStarter` 가 셔플 + 분배 + Redis 저장 → RoomPage 가
   `IN_GAME` 감지 → `GameTable` 마운트 → STOMP CONNECT + `/api/rooms/{id}/resync` →
   본인 손패 수신. (정원만 차면 자동 시작하던 방식은 Phase 16(#2)에서 폐기됐다.)
5. Mahjong 보유자가 첫 리드. 카드 클릭으로 선택 → "내기" → 다른 클라들이 자동 갱신.

스컬킹은 같은 화면에서 방 만들기 시 인원(2~8)을 고르고, 시작하면 예측 제출 → 전원 제출 후
동시 공개 → 트릭 플레이 순서로 진행된다.

**재접속 시나리오**: 게임 중 한 명이 새로고침 / 탭 닫고 다시 열기 → 동일 토큰으로
복귀 → `useStompRoom` 이 `/resync` 호출 → 게임 상태 (TableView + 본인 손패) 즉시
복원. 다른 플레이어 상태는 변하지 않음.

## 분산 시연 (멀티 인스턴스, Phase 6D)

기본은 단일 인스턴스 + `mirboard.messaging.gateway=in-memory`. 멀티 인스턴스에서
STOMP broadcast / 도메인 이벤트를 Redis Pub/Sub 위에서 흐르게 하려면:

```bash
# 터미널 1 — 8080 인스턴스
export MIRBOARD_MESSAGING_GATEWAY=redis
export MIRBOARD_JWT_SECRET="local-dev-secret-must-be-at-least-32-bytes-long-please"
MIRBOARD_PORT=8080 ./gradlew :server:bootRun

# 터미널 2 — 8081 인스턴스 (같은 Redis 사용)
export MIRBOARD_MESSAGING_GATEWAY=redis
export MIRBOARD_JWT_SECRET="local-dev-secret-must-be-at-least-32-bytes-long-please"
MIRBOARD_PORT=8081 ./gradlew :server:bootRun
```

검증 시나리오:
1. 클라 A 가 `ws://localhost:8080/ws`, 클라 B 가 `ws://localhost:8081/ws` 로 STOMP 연결.
2. 둘 다 `/topic/lobby/rooms` 구독.
3. 클라 A 가 `POST http://localhost:8080/api/rooms` 로 방 생성.
4. 클라 B 가 `ROOM_UPDATED` 이벤트 수신 — Redis Pub/Sub 으로 다른 인스턴스에 전파됨.

Sticky session 불필요 — 사용자가 어느 인스턴스에 붙어 있든 자신의 인스턴스 broker
가 STOMP 프레임을 전달. 방 입장 시 `room:{id}` HASH / `room:{id}:players` LIST 가
Redis 단일 진실 공급원이라 두 인스턴스가 같은 상태를 본다.

**한계 (현재 시점)**:
- ApplicationEvent 의 인스턴스 간 fan-out 은 `DomainEventBus` 가 처리하지만 동일
  이벤트가 두 번 처리되지 않게 `instanceId` 만으로 dedup — 발행 인스턴스 재시작 시
  유실 가능성 있음 (현재 MVP 범위에선 무시).
- 게임 액션 처리 락 (`room:{id}:lock`) 은 Redis SET NX 라 이미 분산 안전.

## Phase 6 시연 체크리스트

Phase 6 (E/A/C/D) 의 주요 UX/운영 기능을 사용자 직접 클릭으로 검증할 시나리오 모음.
사전 코드 점검은 완료 — 모든 시나리오 진행 가능. 실패 시 디버깅 포인트는 각 항목
끝에 명시.

### 시나리오 1 — Mahjong 소원 (6E-1)

1. 4탭으로 게임 시작 (정원 4 + 전원 준비 → IN_GAME).
2. Dealing(8) → Dealing(14) → Passing → Playing 진입까지 Ready/카드 패스 진행.
3. 첫 리드 차례 (헤더 `현재 차례`) 가 Mahjong 보유자 (손패에 rank=1 카드) 인 탭에서
   Mahjong 단독 클릭 → "내기".
4. **기대**: `MakeWishModal` 자동 노출. rank 2~14 그리드 + 건너뛰기 / 소원 지정.
5. rank 선택 후 "소원 지정" → 헤더의 `활성 소원: N` 표시.
6. 다른 클라 탭 헤더에서도 동일 `활성 소원: N` 확인 → STOMP fan-out 정상.
7. **실패 시**: 서버 로그 `Action rejected: MAKE_WISH reason=WISH_OUT_OF_CONTEXT` 검색.
   currentTopSeat 이 본인이고 currentTop 이 Mahjong 단독이어야 함.

### 시나리오 2 — Dragon 트릭 양도 (6E-2)

1. Dragon 보유자가 단독 리드 또는 다른 카드 위에 Dragon 단독 플레이.
2. 다른 3명이 모두 PASS → 트릭이 본인에게 닫힘.
3. **기대**: `GiveDragonTrickModal` 자동 노출. 상대팀 두 좌석만 표시.
4. 한 좌석 선택 후 "양도" → 헤더 `누적 A:B` 변화 (Dragon +25 + 트릭 카드 점수).
5. **실패 시**: BOMB 으로 누가 깼다면 currentTop 이 Dragon 아님 → 모달 안 뜸. 정상.
   currentTurnSeat 이 본인이 아니면 트릭이 아직 안 닫힌 상태.

### 시나리오 3 — Phoenix 단독 SINGLE (6E-3)

1. 다른 단일 카드 위에 Phoenix 단독 SINGLE 플레이.
2. **기대**: 트릭 영역에 보라색 "Phoenix +0.5" 배지 + 호버 시 비교 룰 툴팁
   (Dragon 만 못 이김).
3. 다음 플레이어가 더 높은 SINGLE 로 이기면 currentTop 변경 + 배지 사라짐.

### 시나리오 4 — 관전 모드 (6A-5/6A-6)

1. 5번째 사용자 (예: `spec_user`) 가 미르보드카페(`/games`) 진입 → "방 ID 로 관전 진입" 입력 박스에
   IN_GAME 방 ID 붙여넣기 → "구경하기".
2. **기대**: GameTable 표시되되 손패 영역 / 액션 버튼 / 모달 모두 숨김.
   "관전 중 — 본인 손패는 표시되지 않습니다." 배너 노출.
3. "나가기" 버튼 → `DELETE /api/rooms/{id}/spectate` 호출 → 미르보드카페로 복귀.
4. **추가 보안 검증** (옵션): 관전자가 `curl -X POST /app/room/{id}/action` 직접
   호출 시 `NOT_IN_ROOM` 에러 (실제 UI 에선 액션 버튼 자체가 안 보임).

### 시나리오 5 — 멀티 인스턴스 Redis fan-out (6D)

위 "분산 시연 (멀티 인스턴스, Phase 6D)" 섹션의 단계 따라 진행.

추가 확인: `redis-cli MONITOR` 로 `PUBLISH stomp:routes ...` 와 `PUBLISH domain:event
...` 명령이 흐르는지 관찰.

### 운영 카운터 점검 (시연 도중)

```bash
curl -s http://localhost:8080/actuator/prometheus | grep '^mirboard_'
```

기대 출력:
- `mirboard_room_created_total`
- `mirboard_room_joined_total`
- `mirboard_game_started_total{gameType="TICHU"}`
- `mirboard_round_completed_total`
- `mirboard_match_completed_total`
- `mirboard_action_rejected_total`

각 카운터가 0 이상의 값으로 나오면 6A-3/6A-4 정상.

### 로그 MDC 확인

서버 stdout 의 로그 라인이 `HH:mm:ss.SSS LEVEL [thread] logger [user=N room=R event=-]
- msg` 형식인지 확인. 액션 처리 / 방 변경 시 `user=` 와 `room=` 가 채워지면 6A-1 성공.

### 시연 실패 시 보고 가이드

각 시나리오 실패 시 다음 4가지를 함께 보고:
1. 실패한 시나리오 번호 + 단계.
2. 서버 stdout 의 마지막 ~30줄 (특히 `Action rejected` 또는 `Failed to ...`).
3. 브라우저 콘솔의 STOMP 메시지 / API 응답 에러.
4. 재현 가능한지 (1회성 / 반복).