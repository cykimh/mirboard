# Mirboard REST API 명세 (Phase 1)

본 문서는 로비/인증/방 관리/재접속에 대한 REST 엔드포인트 계약이다. 인게임 실시간
이벤트는 STOMP 채널에서 처리되며 `docs/stomp-protocol.md` 를 참조한다.

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
`GAME_NOT_AVAILABLE`, `RESYNC_NOT_AVAILABLE`.

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
에러: `BAD_CREDENTIALS`.

### GET `/api/me`
응답 `200`
```json
{ "userId": 17, "username": "alice_01", "winCount": 3, "loseCount": 4 }
```

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

응답 `200`
```json
{
  "rooms": [
    {
      "roomId": "8f1e...",
      "name": "티츄 한 판",
      "gameType": "TICHU",
      "status": "WAITING",
      "hostId": 17,
      "playerCount": 2,
      "capacity": 4,
      "createdAt": 1715599900000
    }
  ]
}
```

### POST `/api/rooms`
요청
```json
{ "name": "티츄 한 판", "gameType": "TICHU" }
```
- `gameType` 은 `GameRegistry` 에 등록되고 `status==AVAILABLE` 인 ID여야 한다.
- `capacity` 는 서버가 `GameDefinition.maxPlayers()` 로 결정한다 (클라가 보내도 무시).

응답 `201` — Room (위 형식과 동일, 본인이 host로 자동 join 됨).
에러: `INVALID_INPUT` (gameType 미등록 또는 COMING_SOON/DISABLED 상태).

### POST `/api/rooms/{roomId}/join`
응답 `200` — Room 갱신본.
에러: `ROOM_FULL`, `ROOM_NOT_FOUND`, `ALREADY_IN_ROOM`, `GAME_ALREADY_STARTED`.

성공 시 서버는 `/topic/lobby/rooms` 로 `ROOM_UPDATED` 브로드캐스트.

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
  "tier": "SILVER"
}
```

tier 는 derived (rating 구간에서 계산): BRONZE <1100 / SILVER 1100–1249 / GOLD
1250–1399 / PLATINUM 1400–1549 / DIAMOND 1550–1699 / MASTER ≥1700.

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
- 마지막 인원이 떠나면 방 삭제.
- 게임이 이미 진행 중이라면 leave는 "연결 종료(disconnect)"로 처리되며 방은
  유지된다(재접속 가능). 명시적 leave는 게임 포기로 간주하되, 정책은 Phase 4 에서
  확정한다.

### GET `/api/rooms/{roomId}`
응답 `200` — 단일 Room 상세.

### GET `/api/rooms/{roomId}/resync` *(인게임 재접속)*
방 참가자만 호출 가능. 응답에는 본인 손패가 포함되므로 절대 다른 유저에게 노출
금지.

응답 `200`
```json
{
  "roomId": "8f1e...",
  "phase": "PLAYING",
  "eventSeq": 142,
  "tableView": {
    "currentTurnUserId": 19,
    "scores": { "A": 240, "B": 100 },
    "handCounts": { "17": 5, "18": 8, "19": 11, "20": 9 },
    "currentTrick": { "leadUserId": 18, "plays": [ /* 카드 공개 */ ] },
    "tichuDeclarations": { "17": "TICHU", "20": "GRAND" },
    "activeWish": 7
  },
  "privateHand": {
    "cards": [
      { "suit": "JADE", "rank": 9 },
      { "special": "PHOENIX" }
    ]
  }
}
```
에러: `NOT_IN_ROOM`, `RESYNC_NOT_AVAILABLE` (게임 진행 중이 아님).

---

## 사용자 통계

### GET `/api/me/stats`
응답 `200`
```json
{
  "winCount": 3,
  "loseCount": 4,
  "lastMatches": [
    { "matchId": 88, "finishedAt": 1715000000000, "team": "A",
      "isWin": true, "teamAScore": 1050, "teamBScore": 720 }
  ]
}
```

---

## 보안 / 운영 메모

- JWT 시크릿은 환경변수 `MIRBOARD_JWT_SECRET` 로만 주입. 코드/리포에 하드코딩 금지.
- 로그인/회원가입 엔드포인트는 username/password 외 일체의 헤더/쿠키 식별자를
  기록하지 않는다 (IP 로깅은 운영 보안 차원에서 인프라 레이어에서만).
- 모든 응답 헤더에 `Cache-Control: no-store` (인증/방 조회).
