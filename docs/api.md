# Mirboard REST API 명세

본 문서는 로비/인증/방 관리/재접속에 대한 REST 엔드포인트 계약이다. 인게임 실시간
이벤트는 STOMP 채널에서 처리되며 `docs/stomp-protocol.md` 를 참조한다.

> 정본(계약). 2026-07-30 컨트롤러 전수 대조로 정합화(T0) — 응답 형태는 서버 record
> 및 클라 `types/api.ts`·`types/tichu.ts` 미러와 1:1 이다. 코드생성기가 없어 미러가
> 수동이므로, 계약 변경은 **서버 DTO + 클라 타입 + 본 문서를 같은 커밋**으로 처리한다
> (`docs/plans/parallel-tracks.md` 직렬화 지점 4).

## 공통

- Base path: `/api`
- Content-Type: `application/json; charset=UTF-8`
- 인증: `Authorization: Bearer <accessToken>` (JWT, HS256, 12h)
- 시각: 응답의 모든 timestamp는 `epochMillis` (number) — 클라가 로케일 변환.

### 에러 응답 (공통)
```json
{
  "error": {
    "code": "ROOM_FULL",
    "message": "Room capacity exceeded",
    "details": { "roomId": "8f1e..." }
  }
}
```
대표 코드: `INVALID_INPUT`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`,
`USERNAME_TAKEN`, `BAD_CREDENTIALS`, `ROOM_FULL`, `ROOM_NOT_FOUND`,
`ALREADY_IN_ROOM`, `NOT_IN_ROOM`, `GAME_ALREADY_STARTED`,
`GAME_NOT_AVAILABLE`, `RESYNC_NOT_AVAILABLE`,
`TOO_MANY_REQUESTS` (429, 레이트리밋 초과), `ACCOUNT_LOCKED` (423, 로그인 실패 누적 잠금) *(D-84)*.

---

## 인증

### POST `/api/auth/register`
회원가입. 개인정보는 username/password 외 일체 받지 않는다.

요청
```json
{ "username": "alice_01", "password": "s3cret-pass" }
```
- `username`: `^[A-Za-z0-9_]{3,20}$`
- `password`: 8~64자

응답 `201`
```json
{ "userId": 17, "username": "alice_01" }
```

에러: `USERNAME_TAKEN`, `INVALID_INPUT`.

### POST `/api/auth/login`
요청
```json
{ "username": "alice_01", "password": "s3cret-pass" }
```
응답 `200`
```json
{
  "accessToken": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "expiresAt": 1715600000000,
  "user": { "userId": 17, "username": "alice_01" }
}
```
에러: `BAD_CREDENTIALS`, `ACCOUNT_LOCKED` (423 — 실패 누적 잠금, D-84),
`TOO_MANY_REQUESTS` (429 — IP 레이트리밋 초과, D-84),
`ACCOUNT_SUSPENDED` (403 — 어드민 정지, D-86).

### GET `/api/me`
응답 `200`
```json
{ "userId": 17, "username": "alice_01", "winCount": 3, "loseCount": 4 }
```
> D-82: 내기 칩은 계정에 두지 않는다(방 단위 테이블 칩 — `room:{id}:chips`). `/api/me`
> 응답에 칩 잔액 없음.

### PUT `/api/me/password` *(D-85 — 본인 비밀번호 변경)*
요청 (인증 필요)
```json
{ "currentPassword": "old-pass-1", "newPassword": "new-pass-12" }
```
현재 비밀번호 재검증 → `PasswordPolicy`(8~64자) 검증 → BCrypt 재해시 후 `users.password_hash`
갱신. **스키마 무변경**. 응답 `204`. 변경 후 기존 발급 JWT 는 만료(12h)까지 유지된다(D-85).
에러: `BAD_CREDENTIALS` (401 — 현재 비번 불일치), `INVALID_INPUT` (400 — 새 비번 정책 위반).

---

## 게임 카탈로그 (Game Hub)

### GET `/api/games`
로그인 후 Game Hub 화면에서 표시할 보드게임 목록. 진실 공급원은 서버의
`GameRegistry` 빈. 신규 게임은 백엔드에 `GameDefinition` Bean 추가만으로 자동 노출.

응답 `200`
```json
{
  "games": [
    {
      "id": "TICHU",
      "displayName": "티츄",
      "shortDescription": "4인 파트너 카드 게임. 56장 덱과 4장의 특수카드.",
      "minPlayers": 4,
      "maxPlayers": 4,
      "status": "AVAILABLE"
    },
    {
      "id": "GO",
      "displayName": "바둑",
      "shortDescription": "추후 추가 예정",
      "minPlayers": 2,
      "maxPlayers": 2,
      "status": "COMING_SOON"
    }
  ]
}
```
- `status` 값: `AVAILABLE` (플레이 가능) / `COMING_SOON` (UI에서 비활성화 표시) /
  `DISABLED` (응답에서 제외).
- 정렬: `AVAILABLE` 우선, 그 안에서 displayName 가나다 순.

### GET `/api/games/{gameId}`
단일 게임 상세. 응답은 위 항목 형식과 동일하되 룰 요약 등 추가 필드가 들어갈 수 있다
(MVP에서는 카탈로그와 동일 페이로드).

에러: `NOT_FOUND` — 등록되지 않은 gameId.

---

## 방 (Lobby)

### GET `/api/rooms`
쿼리:
- `gameType` (선택, 미지정 시 모든 게임 — 일반적으로 클라이언트는 항상 명시).
- `status` (선택, 기본 `WAITING`. `IN_GAME|FINISHED|ALL` 도 지원).

`gameType` 값은 `GameRegistry` 에 등록된 ID여야 하며, 미등록 값이면 `INVALID_INPUT`
응답.

응답 `200` — `{ "rooms": Room[] }`.

**`Room` 형태** (서버 `Room` record 전체 — 목록·상세·join·ready 등 Room 을 돌려주는
모든 응답이 동일):
```json
{
  "roomId": "8f1e...",
  "name": "티츄 한 판",
  "gameType": "TICHU",
  "hostId": 17,
  "status": "WAITING",
  "capacity": 4,
  "playerCount": 2,
  "playerIds": [17, 18],
  "spectatorIds": [],
  "teamPolicy": "SEQUENTIAL",
  "createdAt": 1715599900000,
  "fillWithBots": false,
  "botSeats": [],
  "targetScore": 1000,
  "turnSeconds": 0,
  "stake": 0,
  "readyUserIds": []
}
```
- 좌석(seat)은 `playerIds` 의 인덱스다 — STOMP 이벤트의 `seat` 와 같은 축.
- `botSeats` 는 `playerIds` 인덱스 기준 봇 좌석(D-50).

### POST `/api/rooms`
요청
```json
{ "name": "티츄 한 판", "gameType": "TICHU", "stake": 100 }
```
- `gameType` 은 `GameRegistry` 에 등록되고 `status==AVAILABLE` 인 ID여야 한다.
- `capacity` 는 서버가 `GameDefinition.maxPlayers()` 로 결정한다 (클라가 보내도 무시).
- 선택: `teamPolicy`, `fillWithBots`, `targetScore`, `turnSeconds`, `stake`(D-81).
- `stake`(D-81): 판돈(가상 칩). 허용값 `{0,10,50,100,500}`(기본 0=내기 없음). 생성 시
  고정·불변. **stake>0 이면 `fillWithBots` 불가**(봇=무한 잔액 → 칩 파밍 방지).

응답 `201` — Room (위 형식과 동일, 본인이 host로 자동 join 됨).
에러: `INVALID_INPUT` (gameType 미등록 또는 COMING_SOON/DISABLED 상태),
`INVALID_STAKE` (허용값 외 판돈), `STAKED_ROOM_NO_BOTS` (판돈 방 + 봇 동시 요청).

### POST `/api/rooms/{roomId}/join`
응답 `200` — Room 갱신본.
에러: `ROOM_FULL`, `ROOM_NOT_FOUND`, `ALREADY_IN_ROOM`, `GAME_ALREADY_STARTED`.

### POST `/api/rooms/{roomId}/rematch` *(D-82)*
호스트가 매치 종료 후 같은 4명·같은 테이블에서 '한 판 더'(리매치). 방은 IN_GAME 을 유지한
채 새 매치를 시작하고, 방 단위 테이블 칩(`room:{id}:chips`)은 누적되며 판돈 미만 보유자는
무료 재바이인된다. 사람 4인 매치만(봇 매치는 종료 후 FINISHED). 응답 `200` — Room.
에러: `NOT_HOST`, `GAME_NOT_IN_PROGRESS`(매치가 아직 안 끝났거나 진행 중 아님), `ROOM_NOT_FOUND`.

성공 시 서버는 `/topic/lobby/rooms` 로 `ROOM_UPDATED` 브로드캐스트.

> **Phase 16(#2)**: join 은 더 이상 정원 도달로 게임을 시작하지 않는다. 시작은
> 전원이 `/ready` 로 준비 완료한 시점(아래).

### POST `/api/rooms/{roomId}/ready` *(Phase 16 #2)*
대기실 준비 토글. body `{ "ready": true|false }`. 응답 `200` — Room 갱신본
(`readyUserIds` 포함). 좌석에 앉은 플레이어만 호출 가능. 정원이 모두 모이고
전원 ready(봇은 join 시 서버가 자동 ready) 가 되면 서버가 원자적으로
WAITING→IN_GAME 전이 + 게임 시작. 에러: `ROOM_NOT_FOUND`,
`GAME_ALREADY_STARTED`(이미 시작/종료), `NOT_IN_ROOM`(미착석).

### POST `/api/rooms/{roomId}/join-or-reconnect` *(Phase 8A)*
직접 링크 진입 시나리오. 본인 상태에 따라 자동 분기:
- `JOINED` — WAITING 방 + 빈 자리 있을 때 새로 입장.
- `RECONNECTED` — 이미 본인이 `playerIds` 에 있음 (Redis 변경 없음, 좌석 보존).
- `SPECTATING` — IN_GAME 방에 들어왔거나 capacity 가 찬 경우 자동 관전.

응답 `200`
```json
{ "mode": "JOINED" | "RECONNECTED" | "SPECTATING", "room": { ... } }
```
에러: `ROOM_NOT_FOUND`. 손패 노출 방지 차원에서 비-참여자는 절대 player 로 흡수되지 않음.

### GET `/api/users/{userId}/stats` *(Phase 8D)*
ELO rating + 누적 전적 + 파생 tier 반환. 식별 정보 (email/phone 등) 노출 0건 — D-02
schema constraint 유지.

응답 `200`
```json
{
  "userId": 17,
  "username": "alice",
  "winCount": 5,
  "loseCount": 3,
  "rating": 1120,
  "tier": "SILVER",
  "desertCount": 1
}
```

`desertCount` (Phase 19#3, D-75): IN_GAME 탈주(명시 '나가기' / 끊김 후 유예
미복귀) 누적. 게임행동 derived — D-02 위반 아님.

tier 는 derived (rating 구간에서 계산): BRONZE <1100 / SILVER 1100–1249 / GOLD
1250–1399 / PLATINUM 1400–1549 / DIAMOND 1550–1699 / MASTER ≥1700.

### GET `/api/users/ranking` *(Phase 16 #5)*
쿼리 `limit` (기본 20, 1~100 clamp). 봇 제외, rating 내림차순(동점 시 id 오름차순).
username 외 식별 정보 노출 0건 — D-02 constraint.

응답 `200`
```json
{
  "entries": [
    { "rank": 1, "userId": 17, "username": "alice", "rating": 1240,
      "tier": "GOLD", "winCount": 12, "loseCount": 4, "desertCount": 0 }
  ]
}
```

### GET `/api/users/names` *(A6 — 좌석 닉네임 표시)*
쿼리 `ids` (userId 콤마 목록, 중복 제거 후 최대 50). 존재하지 않는 id 는 조용히
누락 — 클라는 `#id` 폴백.

응답 `200`
```json
{ "names": [ { "userId": 17, "username": "alice" } ] }
```

### PUT `/api/rooms/{roomId}/team-policy` *(Phase 8C — 호스트 전용)*
WAITING 방의 팀 배정 정책을 변경. IN_GAME / FINISHED 방은 거절.

요청
```json
{ "teamPolicy": "SEQUENTIAL" | "RANDOM" | "MANUAL" }
```

응답 `200` — Room 갱신본. 에러: `NOT_HOST` (403), `GAME_ALREADY_STARTED` (409),
`ROOM_NOT_FOUND` (404).

본 청크 (Phase 8C) 에서는 `RANDOM` 만 서버 동작 분기 (4번째 join 직후 좌석 셔플).
`MANUAL` 은 enum 만 예약, 서버 동작은 `SEQUENTIAL` 과 동일 — 후속 청크에서 호스트
좌석 드래그 UI 도입 시 분기 추가.

### POST `/api/rooms/{roomId}/abort` *(Phase 8A — 호스트 전용)*
IN_GAME 방을 강제 종료. 무한 재접속 정책 하에서 끊긴 플레이어가 돌아오지 않을 때
유일한 탈출구.

응답 `204`. 에러: `NOT_HOST` (403), `GAME_NOT_IN_PROGRESS` (409), `ROOM_NOT_FOUND` (404).
방 status → `FINISHED`, `/topic/lobby/rooms` 로 `ROOM_UPDATED` 브로드캐스트.

### POST `/api/rooms/{roomId}/leave`
응답 `204`.
- 호스트가 떠나면 잔존 인원 중 가장 먼저 입장한 사용자가 호스트 승격.
- 마지막 인원이 떠나면 방 삭제(`room_leave.lua` 가 players/ready/spectators
  정리). 관전자만 남았다가 0이 되면 `room_delete.lua` 로 즉시 소멸.
- Phase 19(#3, D-75): 방이 **IN_GAME** 이고 호출자가 플레이어면 명시적
  leave 는 **탈주**로 처리 — 상대팀 승리로 매치 즉시 종료, 탈주자
  `desert_count`+1 · `lose_count`+1 · ELO 차감(봇 포함 매치는 ELO 제외,
  D-71). WAITING/FINISHED 이거나 관전자면 일반 leave/stopSpectating.
- WS 끊김(새로고침/탭닫기)은 서버 SessionDisconnect 후킹이 처리: WAITING
  은 즉시 leave, IN_GAME 은 유예(`mirboard.desertion.grace-seconds`,
  기본 **120s**, D-79) 후 미복귀 시 탈주.

### GET `/api/rooms/{roomId}`
응답 `200` — 단일 Room 상세.

### POST `/api/rooms/{roomId}/spectate` *(Phase 6A-5 — 관전 진입)*
응답 `200` — Room (spectatorIds 에 본인 추가). 플레이어로 이미 참가 중이면 에러.

### DELETE `/api/rooms/{roomId}/spectate`
관전 종료. 등록 안 되어 있어도 `204`.

### GET `/api/rooms/{roomId}/resync` *(인게임 재접속·관전 동기화)*
**참가자 + 관전자** 호출 가능(그 외 `NOT_IN_ROOM`). 관전자는 `privateHand: null`
— 본인 손패는 참가자 본인에게만 (State Hiding).

응답 `200` (필드는 서버 `ResyncResponse`/`TableView`/`PrivateHand` record,
클라 `types/tichu.ts` 미러와 1:1)
```json
{
  "roomId": "8f1e...",
  "phase": "PLAYING",
  "eventSeq": 142,
  "tableView": {
    "phase": "PLAYING",
    "dealingCardCount": 14,
    "readySeats": [],
    "passingSubmittedSeats": [],
    "currentTurnSeat": 2,
    "handCounts": { "0": 5, "1": 8, "2": 11, "3": 9 },
    "currentTop": { "type": "PAIR", "cards": [/* Card[] */], "rank": 7,
                    "length": 2, "phoenixSingle": false },
    "currentTopSeat": 1,
    "declarations": { "0": "TICHU", "3": "GRAND_TICHU" },
    "roundScores": { "A": 40, "B": 15 },
    "matchScores": { "A": 240, "B": 100 },
    "roundNumber": 3,
    "finishingOrder": [],
    "activeWishRank": 7
  },
  "privateHand": { "seat": 0, "cards": [
    { "suit": "JADE", "rank": 9, "special": null },
    { "suit": null, "rank": 0, "special": "PHOENIX" }
  ]},
  "disconnectedSeats": [3],
  "chips": { "17": 1000, "18": 900, "19": 1100, "20": 1000 }
}
```
- 좌석 식별은 **seat(0~3, playerIds 인덱스)**, `handCounts`/`declarations` 키도 seat.
- `disconnectedSeats`: 현재 끊긴 플레이어 좌석(재접속 배지 즉시 반영, D-75).
- `chips`: D-82 방 단위 테이블 칩(userId→칩). 내기 없는 방은 빈 맵.

에러: `NOT_IN_ROOM`, `RESYNC_NOT_AVAILABLE` (게임 진행 중이 아님).

---

## 사용자 통계

본인 전적은 별도 엔드포인트가 아니라 `GET /api/users/{userId}/stats` 를 본인 id 로
호출한다(위). 매치 이력 목록(`lastMatches`) 은 **미구현** — `tichu_match_results` /
`tichu_match_participants` 에 적재는 되고 있으나 조회 API 는 없다.

---

## 아바타 *(D-80 — 선택적 코스메틱, 별도 테이블 `user_avatars`)*

### POST `/api/me/avatar`
multipart `file` — 서버가 128px PNG 로 정규화해 BYTEA 저장(upsert). 응답 `204`.
에러: `INVALID_AVATAR` (빈 파일/미지원 형식), 업로드 크기 초과.

### DELETE `/api/me/avatar`
응답 `204` (없어도 204).

### GET `/avatars/{userId}` *(공개, 비-`/api`)*
`image/png` 바이너리 (`Cache-Control: max-age=60`), 없으면 `404`. `<img>` 직접
요청은 Bearer 를 못 실으므로 의도적으로 `/api/**` 밖(D-61 default-permit) —
`users` 화이트리스트 불변(D-02).

---

## 채팅 신고 *(D-93)*

### POST `/api/chat/reports`
채팅 메시지 신고. **본문(message)을 보내지 않는다** — `eventId` 로 "어느 메시지"만
지목하고 원문·작성자는 서버가 Redis 링버퍼(`chatlog:*`, TTL 2h·최근 100개) 보관분에서
확정한다. 클라가 본문을 제출하면 "상대가 이런 말을 했다"를 위조할 수 있기 때문
(Server-Authoritative). 신고된 메시지만 `chat_reports`(V9)로 승격되며 상시 채팅 로그는
영속화하지 않는다.

요청
```json
{ "eventId": "9d6f-...", "scope": "ROOM", "roomId": "8f1e..." }
```
- `eventId`: STOMP `CHAT` envelope 의 `eventId` (클라 `roomChatStore` 가 보관 중)
- `scope`: `"ROOM"` | `"LOBBY"` (대소문자 무시). `ROOM` 이면 `roomId` 필수.

응답 `201`
```json
{ "reportId": 12, "eventId": "9d6f-..." }
```

에러:
- `CHAT_MESSAGE_NOT_FOUND` (404) — 링버퍼에 없음. 대개 너무 오래된 메시지(TTL 2h·100개 초과).
- `SELF_REPORT` (400) — 자기 메시지 신고.
- `DUPLICATE_REPORT` (409) — 같은 사람이 같은 메시지 재신고(`UNIQUE(event_id, reporter_user_id)`).
- `TOO_MANY_REQUESTS` (429) — D-90 `expensive-write` 버킷.

---

## 어드민 / 모더레이션 *(D-86 — 어드민만)*

`/api/admin/**` 은 `admin_roles` 에 등록된 사용자만 접근(매 요청 조회). 비어드민은
`NOT_ADMIN` (403). 어드민 부여는 DB insert(운영 스크립트)로만.

### POST `/api/admin/rooms/{roomId}/abort`
어드민이 진행 중(IN_GAME) 매치를 강제 종료. host 검증 없음(host용 `/api/rooms/{id}/abort`
와 분리). 응답 `204`. 에러: `NOT_ADMIN` (403), `GAME_NOT_IN_PROGRESS` (409), `ROOM_NOT_FOUND` (404).

### POST `/api/admin/users/{userId}/suspend`
유저 정지. 본문 `{ "minutes": 60 }`(선택, 기본 60분, 1~525600 클램프). 정지 상태는 Redis
TTL(`suspend:user:{id}`)에만 둔다(users 스키마 비침범). 정지된 유저는 로그인·STOMP CONNECT
차단(`ACCOUNT_SUSPENDED` 403). 기존 발급 토큰의 활성 소켓은 만료까지 유지. 응답 `204`.
에러: `NOT_ADMIN` (403).

### DELETE `/api/admin/users/{userId}/suspend`
유저 정지 해제. 응답 `204`. 에러: `NOT_ADMIN` (403).

### GET `/api/admin/chat-reports` *(D-93)*
채팅 신고 목록(최신순). 쿼리 `limit` 기본 50, 1~200 clamp.
각 항목에 피신고자의 **누적 신고 수**(`totalAgainstReported`)를 함께 실어 정지 판단을
한 화면에서 할 수 있게 한다.

응답 `200`
```json
{
  "reports": [
    {
      "reportId": 12,
      "eventId": "9d6f-...",
      "scope": "ROOM",
      "roomId": "8f1e...",
      "reportedUserId": 18,
      "reporterUserId": 17,
      "message": "신고된 메시지 본문",
      "messageAt": 1715600000000,
      "createdAt": 1715600030000,
      "totalAgainstReported": 3
    }
  ]
}
```
- `message` 는 broadcast 된 본문 그대로다 — D-86 금칙어 마스킹이 이미 적용된 상태.
  어드민이 보는 것과 사용자가 본 것을 일치시키기 위함.
- 처리 상태(resolve/dismiss)는 **미구현**(D-93 범위 밖).

에러: `NOT_ADMIN` (403).

---

## 보안 / 운영 메모

- JWT 시크릿은 환경변수 `MIRBOARD_JWT_SECRET` 로만 주입. 코드/리포에 하드코딩 금지.
- 로그인/회원가입 엔드포인트는 username/password 외 일체의 헤더/쿠키 식별자를
  기록하지 않는다 (IP 로깅은 운영 보안 차원에서 인프라 레이어에서만).
- 모든 응답 헤더에 `Cache-Control: no-store` (인증/방 조회).
- *(D-83)* CORS origin 은 `mirboard.security.allowed-origins` 화이트리스트로 고정
  (전면 개방 `*` 폐지). 보안 헤더 `X-Content-Type-Options=nosniff`,
  `X-Frame-Options=DENY`, `Referrer-Policy=strict-origin-when-cross-origin`, HSTS(HTTPS 한정).
- *(D-84)* 로그인 brute-force 잠금 + 인증 엔드포인트 IP 레이트리밋. 잠금/카운터는
  전부 Redis(휘발, TTL)에 두어 `users` 스키마 불변(D-02 준수). 레이트리밋 버킷 키에
  쓰는 클라이언트 IP 는 TTL 휘발값이며 영속 로그가 아니다(위 IP 비기록 원칙과 일관).
