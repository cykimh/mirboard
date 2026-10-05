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

## 게스트 체험 (D-117)

가입 없이 들어온 방문자가 한 판을 끝까지 하고, 게스트 제한이 지켜지는지:

```bash
# 1) 게스트 생성 — 201, user.guest=true, username 은 guest-xxxxxxxx
G=$(curl -s -X POST http://localhost:8080/api/auth/guest \
  -H "Content-Type: application/json" -d '{}')
echo "$G" | jq .user
GT=$(echo "$G" | jq -r .accessToken)

# 2) 게스트 금지 행위 — 둘 다 403 GUEST_FORBIDDEN
curl -s -X PUT http://localhost:8080/api/me/password -H "Authorization: Bearer $GT" \
  -H "Content-Type: application/json" -d '{"currentPassword":"x","newPassword":"newpass123"}'
curl -s -X DELETE http://localhost:8080/api/me/avatar -H "Authorization: Bearer $GT"

# 3) 게스트 username 으로는 로그인할 수 없다 — 401 BAD_CREDENTIALS
curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d "{\"username\":\"$(echo "$G" | jq -r .user.username)\",\"password\":\"__guest_no_login__\"}"

# 4) JSON 이 아니면 415 (크로스사이트 폼 전송 차단)
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/auth/guest \
  -H "Content-Type: text/plain" -d '{}'
```

브라우저(시크릿 창 권장 — 로컬 스토리지가 비어 있어야 처음 온 사람과 같다):

1. 로그인 화면 폼 아래 '또는' + 「게스트로 바로 체험하기」 + 캡션 "가입 없이 12시간 · 전적은
   랭킹에 오르지 않아요" → 누르면 메인으로 간다.
2. 메인 헤더: username 옆 '게스트' 배지, 아바타 버튼은 눌리지 않고 마우스를 올리면 이유가 뜬다.
3. 「새 방 만들기」 → '빈 좌석 봇으로 채우기'가 **켜진 채** 열린다 → 봇 방에서 한 판을 끝낸다.
4. 로비 채팅을 다른 창(정회원)과 주고받는다 — 게스트도 정회원과 같다.
5. 랭킹 카드에 게스트가 없다. 프로필의 비밀번호 칸은 안내 문구로 바뀌어 있다.
6. 「로그아웃」 → "다시 들어올 수 없어요" 확인창. 취소하면 그대로, 확인하면 로그인 화면.
7. 한도: 같은 IP 에서 하루 11번째 생성은 429 → 로그인 화면이 "이 네트워크에서 오늘 만들 수
   있는 게스트 수를 다 썼어요" + 「회원가입하고 시작하기」 버튼을 보여 준다.

운영에서는 배포 직후 [deploy.md](deploy.md) "게스트 체험 → 배포 후 실측"의 IP 위조 내성 루프와
경로 변형(인코딩 경로·`X-Forwarded-Prefix`) 우회 내성 루프(각각 로그인 21회 — 21번째가 429,
루프 사이 1분)를 한 번씩 돌린다.

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

**스컬킹 봇 방 확인 (D-119 휴리스틱)**: 사람 1명 + 봇 채우기로 4~6인 방을 열어 몇 라운드
진행하며 본다.
1. 봇 예측이 **0 고정이 아니라 손패에 따라 달라지는가** — 공개된 예측표에 1·2 이상이 섞인다.
   (전원 0 이면 휴리스틱이 아니라 최약수 경로를 타는 것이다.)
2. 예측을 채운 봇이 이후 트릭에서 **탈출·약한 카드로 빠지는가** — 이미 채운 봇이 스컬킹·해적을
   던져 트릭을 더 가져가는 장면이 반복되면 이상.
3. 사람이 게임 중 '나가기'로 탈주하면 그 좌석(유령)은 **여전히 0 예측·최약수**인가 — 봇
   휴리스틱은 봇 좌석에만 쓴다(`rules-skullking.md` §16.5).
4. 서버 로그에 `SkullKing bot policy ... falling back` ERROR 가 없어야 한다(정책 폴백 = 버그).

**스컬킹 라운드 결과·점수표 (D-120)** — 375×667(모바일)과 데스크톱에서 각각 확인한다.

1. **직전 결과 패널**: 라운드 1 을 마치면 화면은 곧바로 라운드 2 예측으로 넘어간다(서버는
   라운드 사이에 멈추지 않는다). 예측 패널·손패 **아래**에 '라운드 1 결과'(7열 표, ✓/✗,
   내 행 강조)가 보이고, 내 정보줄에 `직전 R1 +20 ✓` 가 함께 뜬다. 예측 버튼이 결과 패널에
   가려지거나 밀리지 않는다. '닫기' 를 누르면 그 라운드 결과만 사라지고, 라운드 2 가 끝나면
   라운드 2 결과가 새로 뜬다. 카드 플레이 단계로 넘어가면 패널은 사라진다.
2. **점수표 모달**: 헤더 '점수표'(또는 패널의 '점수표 전체') → 라운드×좌석 표. 셀은 라운드
   점수·✓/✗·작은 `예측/획득`, 합계 행은 누적 점수와 같다. 8인 방은 가로 스크롤되고 R 열은
   고정된다. 끝난 라운드가 없으면 "아직 끝난 라운드가 없습니다".
3. **새로고침·탭 전환 복원**: 라운드 3 예측 중에 새로고침 → 결과 패널과 점수표가 그대로
   복원된다(resync `completedRounds`). 사람만 있는 방에서 매치가 끝난 뒤 탭을 전환했다
   돌아와도(resync) 매치 종료 패널이 사라지지 않는다(`matchResult`).
4. **관전자**: 관전 진입 시 결과 패널은 좌석 바로 아래에 보이고, 점수표도 열린다.
   입력·손패 패널은 없다.
5. **봇 방 라운드 10 종료 화면 유지**: 사람 1 + 봇 방을 끝까지 진행(턴 제한을 짧게 두면
   빠르다). 매치가 끝나면 방은 FINISHED 가 되지만 게임판이 내려가지 않고 매치 종료 패널
   (승자·최종 점수·접힌 '라운드별 점수' 표)과 '메인으로' 가 남는다. 예측·카드 입력은 숨고,
   '나가기' 는 탈주 확인 없이 나간다. 이 상태에서 새로고침하면 기존 "게임이 종료되었습니다"
   카드다(의도된 동작).
6. **강제 종료**: 진행 중에 호스트 abort(`POST /api/rooms/{id}/abort` — 스컬킹 게임판에는
   버튼이 없다) 또는 어드민 강제 종료(`POST /api/admin/rooms/{id}/abort`)를 하면 스컬킹
   게임판이 남고 "게임이 종료되었습니다" 안내 + '메인으로' 가 뜬다(매치 결과 없음). 방
   해시는 FINISHED 후 600s 뒤 사라지므로 그 뒤 '메인으로'·resync 는 방 없음 오류 화면으로
   떨어진다.
7. **테마·터치**: 라이트/다크 모두 적중(녹)·실패(적) 글자가 배경 대비로 읽힌다. 터치 기기에서
   헤더 점수표·채팅·나가기 버튼이 44px 이상이다.

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

### 시나리오 1 — Mahjong 소원 (D-109)

1. 게임 시작 (정원 + 전원 준비 → IN_GAME). **봇 채우기 방으로도 검증 가능** — 소원 창이
   더 이상 다음 플레이어의 속도에 좌우되지 않는 것이 이 시나리오의 핵심이다.
2. Dealing(8) → Dealing(14) → Passing → Playing 진입까지 Ready/카드 패스 진행.
3. 첫 리드 차례 (헤더 `현재 차례`) 가 Mahjong 보유자 (손패에 rank=1 카드) 인 탭에서
   Mahjong 클릭 → "내기".
4. **기대**: 카드가 **아직 나가지 않고** 소원 모달이 뜬다. rank 2~14 그리드 +
   [소원 없이 내기] / [소원 지정하고 내기]. 모달은 시간이 지나도 닫히지 않는다.
5. **취소 확인**: esc 를 눌러 모달을 닫는다. 카드가 나가지 않고 선택이 유지되어야 한다.
   다시 "내기" 를 눌러 모달을 연다.
6. rank 선택 후 [소원 지정하고 내기] → 카드가 나가면서 헤더에 `활성 소원: N` 표시.
7. 다른 클라 탭 헤더에서도 동일 `활성 소원: N` 확인 → STOMP fan-out 정상.
8. **봇 소원 확인**: 봇이 마작을 리드한 라운드에서도 `활성 소원: N` 이 뜨는지 본다
   (봇(휴리스틱, D-118)은 내 손에 없고 4장이 다 나오지 않은 최고 랭크로 소원을 건다 — 그런
   랭크가 없을 때만 소원 없이 낸다).
9. **실패 시**: 서버 로그 `Action rejected: PLAY_CARD reason=WISH_OUT_OF_CONTEXT` 검색.
   `wishRank` 를 실으려면 낸 카드에 Mahjong 이 포함되어야 한다.
10. **소원 해제 (D-126)**: 누군가 소원 숫자를 내면(또는 소원이 걸린 채 용 트릭을 양도하면) 모든 탭에서
    `활성 소원` 표시가 바로 사라진다. DevTools 네트워크 탭에서 카드를 낼 때 `/resync` 요청이 **나가지
    않는지** 본다 — 라운드가 끝나는 플레이(다음 라운드 시작)와 단계 전환(티츄 선언 마감·패스 교환)에서만
    나가는 것이 정상이다. 예전에는 카드를 낼 때마다 전원이 resync 했다.

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

### 시나리오 6 — 게임별 튜토리얼 (D-121)

1. 새 시크릿 창(빈 localStorage)으로 로그인 → 미르보드카페.
2. **기대**: 허브에서는 튜토리얼이 **자동으로 뜨지 않는다**. 헤더에 '게임 방법' 버튼이 없고,
   티츄·스컬킹 게임 카드마다 '게임 방법' 버튼이 있다(튜토리얼이 없는 게임 카드에는 없음).
3. 스컬킹 카드 '게임 방법' → 스컬킹 13단계, 티츄 카드 → 티츄 9단계가 열린다. 닫는다.
4. 다른 시크릿 창(빈 localStorage)으로 스컬킹 방을 만들거나 입장 → **대기실 첫 입장에 스컬킹
   튜토리얼이 1회 자동으로 뜬다**. 닫고(✕ 또는 마지막 '시작하기') 나갔다 다시 들어오면 안 뜬다.
   대기실 헤더 '게임 방법' 으로는 언제든 다시 연다.
5. 같은 창에서 티츄 방 입장 → 티츄 튜토리얼이 따로 1회 뜬다(게임별 키 격리). 기존에 티츄
   튜토리얼을 본 계정·브라우저(`mirboard.tutorial.seen.v1` 있음)는 티츄 대기실에서 안 뜬다.
6. 스컬킹 게임판 헤더 '규칙' → 같은 튜토리얼이 열린다. 게임판에서는 자동으로 뜨지 않는다.
   턴 제한 방이면 보는 동안에도 타이머가 흐르는 것이 정상이다(title 툴팁 고지).
7. 다이얼로그 안의 카드 칩(② 카드 구성, ⑦~⑨ 예시, ⑫ 퀴즈)이 **색이 채워져** 보이는지
   라이트/다크 양쪽에서 본다. ⑫ "누가 이길까?" 에서 칩을 고르면 정답/해설, '다음 문제'로 4문제 순환.
8. **실패 시**: 칩이 투명(배경 없음)이면 `.tutorial-body` 에 `sk-tokens` 클래스가 붙었는지,
   `18-skullking-table.css` 끝의 `.sk-tokens` 블록이 빌드에 들어갔는지 확인. 자동 노출이 다시
   뜨면 localStorage 의 `mirboard.tutorial.skull_king.seen.v1` 값을 본다.

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