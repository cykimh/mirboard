# Mirboard WebSocket / STOMP 프로토콜

> 정본(계약). 2026-07-30 코드 전수 대조로 정합화(T0) — 이벤트/토픽/페이로드는
> 서버 `TichuEvent`·`GameEventBroadcaster`·각 Publisher, 클라 `useStompRoom`·
> `tichuStore.applyEvent` 와 1:1 이다. 변경 시 서버 DTO·클라 미러와 같은 커밋으로.

## 연결

- 엔드포인트: `ws://<host>/ws` (Spring STOMP + SockJS fallback)
- 헤더: `Authorization: Bearer <JWT>` — `StompAuthChannelInterceptor` 가 CONNECT
  단계에서 검증(정지 계정 차단 포함, D-86), 실패 시 `ERROR` 프레임 후 연결 종료.
- 클라는 CONNECT 직후 자기 큐 `/user/queue/...` 와 필요한 토픽을 구독한다.
- Phase 19(#1·#3, D-75): 서버가 `SessionSubscribeEvent`(`/topic/room/{id}`
  `/meta` `/chat` 정규식 매칭)로 세션→방을 `WsSessionRegistry`(in-memory,
  단일 인스턴스 전제 D-03)에 기록하고, `SessionDisconnectEvent` 시
  `RoomDisconnectHandler` 가 처리한다 — **WAITING**: 즉시 leave/
  stopSpectating(빈 방·관전자0 방 즉시 소멸). **IN_GAME**:
  `DesertionGraceScheduler` 가 `mirboard.desertion.grace-seconds`(기본
  **120s**, D-79) 후 재접속 없으면 탈주 확정(상대팀 승리, `desert_count`+1·
  lose+1·ELO−). **FINISHED**: no-op.

## 토픽 / 큐 카탈로그

| 종류 | 목적지 | 흐름 | 내용 |
| --- | --- | --- | --- |
| TOPIC | `/topic/lobby/chat` | 서버→전체 | 로비 채팅 (`CHAT`) |
| TOPIC | `/topic/lobby/rooms` | 서버→전체 | 방 목록 변경 (`ROOM_UPDATED`, `ROOM_DESTROYED`) |
| TOPIC | `/topic/room/{roomId}` | 서버→방 | 게임 공개 이벤트 + `PLAYER_DIS/RECONNECTED` + `CHIPS_SETTLED` |
| TOPIC | `/topic/room/{roomId}/meta` | 서버→방 | 대기실 메타 (`ROOM_META_UPDATED`, `ROOM_DESTROYED`) — Phase 13C, RoomPage 폴링 대체 |
| TOPIC | `/topic/room/{roomId}/chat` | 서버→방 | 인-게임 채팅 (`CHAT`) — 참가자+관전자 |
| TOPIC | `/topic/room/{roomId}/reaction` | 서버→방 | 이모지 반응 (`REACTION`) |
| QUEUE | `/user/queue/room/{roomId}` | 서버→본인 | `HAND_DEALT`, `CARDS_RECEIVED`, `ERROR` |
| APP   | `/app/lobby/chat` | 클라→서버 | `{ message }` (≤500자) |
| APP   | `/app/room/{roomId}/chat` | 클라→서버 | `{ message }` (≤500자, 참가자·관전자만) |
| APP   | `/app/room/{roomId}/reaction` | 클라→서버 | `{ emoji }` (서버 화이트리스트 8종) |
| APP   | `/app/room/{roomId}/action` | 클라→서버 | 게임 액션 (아래 `@action` 판별) |

## Envelope (서버 → 클라 공통)

```json
{
  "eventId": "9d6f-...",     // 서버가 생성 (dedup 용)
  "seq":     142,             // 방 단위 단조 증가 (room:{id}:seq INCR) — 게임 이벤트만
  "type":    "PLAYED",
  "ts":      1715600000000,
  "payload": { ... }
}
```

- `seq` 는 **게임 이벤트(`/topic/room/{id}` 의 엔진 발행분 + `HAND_DEALT`/
  `CARDS_RECEIVED`)에만** 부여된다. 메타/채팅/반응/프레즌스/`CHIPS_SETTLED`/`ERROR`
  는 `seq: null`. 클라는 `seq <= localSeq` 인 이벤트를 무시(idempotent).
- **클라 → 서버는 envelope 을 쓰지 않는다.** 액션은 `@action` 판별자를 가진
  bare JSON, 채팅/반응은 `{message}`/`{emoji}` 를 그대로 발행한다. 서버는 클라가
  보낸 어떤 seq 도 신뢰하지 않고 자체 카운터만 쓴다.
- **Phase 5d (클라 리듀서 계약)**: reducer 가 정의된 이벤트(PLAYED, PASSED,
  TURN_CHANGED, TRICK_TAKEN, PLAYER_FINISHED, TICHU_DECLARED, WISH_MADE,
  DRAGON_GIVEN, PLAYER_READY, PASSING_SUBMITTED, ROUND_ENDED, MATCH_ENDED,
  PLAYER_DISCONNECTED, PLAYER_RECONNECTED, CHIPS_SETTLED) 는 store 부분 패치로
  직접 반영하고, 라이프사이클 이벤트(DEALING_PHASE_STARTED, PASSING_STARTED,
  CARDS_PASSED, PLAYING_STARTED, ROUND_STARTED) 또는 seq gap
  (`seq > lastSeq + 1`) 에서만 REST `/resync` 로 권위 스냅샷을 재취득한다.
  초기 mount 및 STOMP onConnect 직후 `/resync` 는 유지.

---

## 서버 → 클라 (공개) — `/topic/room/{roomId}`

좌석 식별은 전부 **seat(0~3, playerIds 인덱스)** 기준이다. userId 가 필요한 화면은
`Room.playerIds` 로 매핑한다.

**게임 이벤트 (seq 있음)** — 서버 `TichuEvent.envelopeType()` 과 1:1:

| type | payload | 의미 |
| --- | --- | --- |
| `DEALING_PHASE_STARTED` | `{ phaseCardCount: 8\|14 }` | Dealing 단계 시작/전환 (Phase 5b) |
| `PLAYER_READY` | `{ seat }` | 좌석이 Dealing 윈도우 결정을 마침 |
| `PASSING_STARTED` | `{}` | Dealing(14) 마감, 카드 패스 단계 진입 |
| `PASSING_SUBMITTED` | `{ seat }` | 좌석의 패스 카드 제출 완료 |
| `CARDS_PASSED` | `{}` | 4명 모두 제출 후 동시 스왑 완료 (실제 카드는 비공개 큐 `CARDS_RECEIVED`) |
| `PLAYING_STARTED` | `{ leadSeat }` | Playing 단계 진입, Mahjong 보유자 리드 |
| `ROUND_STARTED` | `{ roundNumber, cumulativeScores: {A,B} }` | 새 라운드 시작 (Phase 5c) |
| `PLAYED` | `{ seat, hand: { type, cards, rank, length, phoenixSingle? } }` | 카드 플레이 결과 |
| `PASSED` | `{ seat }` | 트릭 패스 |
| `TURN_CHANGED` | `{ currentTurnSeat }` | 턴 전환 (만료 시각 필드 없음 — 카운트다운은 클라 로컬 근사, Phase 15#6) |
| `TRICK_TAKEN` | `{ takerSeat, trickPoints }` | 트릭 종료/획득 |
| `TICHU_DECLARED` | `{ seat, kind: "TICHU"\|"GRAND_TICHU" }` | 선언 알림 |
| `WISH_MADE` | `{ rank }` | Mahjong 소원 (해소는 별도 이벤트 없이 상태로 반영) |
| `DRAGON_GIVEN` | `{ fromSeat, toSeat }` | 드래곤 트릭 양도 결과 |
| `PLAYER_FINISHED` | `{ seat, order }` | 좌석 완주 (order 1~) |
| `ROUND_ENDED` | `{ score: { teamAScore, teamBScore, firstFinisherSeat, doubleVictory } }` | 라운드 점수 |
| `MATCH_ENDED` | `{ winningTeam, finalScores: {A,B}, roundsPlayed, mvpUserId?, mvpStat? }` | 매치 종료 (한 팀 ≥목표점 + 점수 다름) |

**메타 이벤트 (seq: null)**:

| type | payload | 의미 |
| --- | --- | --- |
| `PLAYER_DISCONNECTED` | `{ seat }` | 연결 끊김 (D-75) |
| `PLAYER_RECONNECTED` | `{ seat }` | 재접속 완료 |
| `CHIPS_SETTLED` | `{ stacks:{userId:칩}, deltas:{userId:±} }` | D-82 방 단위 테이블 칩(게임 시작 init·매치 종료 정산). 칩 스택은 `/resync` 의 `chips` 로도 제공 |

**별도 토픽의 메타 이벤트**: `ROOM_UPDATED`(방 요약, `/topic/lobby/rooms`),
`ROOM_META_UPDATED`(방 요약, `/topic/room/{id}/meta`), `ROOM_DESTROYED`
(`{ roomId }`, 위 두 토픽 모두), `CHAT`(`{ userId, username, message }`),
`REACTION`(`{ fromSeat, emoji }`).

---

## 서버 → 클라 (비공개) — `/user/queue/room/{roomId}`

| type | payload | 의미 |
| --- | --- | --- |
| `HAND_DEALT` | `{ seat, cards, phaseCardCount: 8\|14 }` | 8장(Dealing 진입) 또는 14장(전환 후) 손패 스냅샷 |
| `CARDS_RECEIVED` | `{ seat, received: [{ card, fromSeat }] }` | 패스로 받은 3장 + 출처 (스왑 직후) |
| `ERROR` | `{ code, message }` | 본인의 잘못된 액션 (seq: null) |

> 재접속 상태 복원은 WS 이벤트가 아니라 **REST `GET /api/rooms/{id}/resync`**
> (docs/api.md) 다. 본인 큐의 메시지는 절대 `/topic` 으로 누출되어선 안 되며,
> 서버 측 직렬화 시 `PrivateHand` 와 `TableView` DTO 를 분리된 타입으로 다룬다.

---

## 클라 → 서버 — `/app/room/{roomId}/action`

envelope 없이 **`@action` 판별자를 가진 bare JSON** 을 보낸다(Jackson
`@JsonTypeInfo(property = "@action")`). 잘못된 액션은 본인 큐 `ERROR` 로만
회신되고 다른 플레이어 상태는 변하지 않는다.

목적지는 게임별로 갈리지 않는다 — 서버가 방의 `gameType` 으로 역직렬화 대상 타입을
고른다(D-98). 아래 표는 **티츄**의 `@action` 목록이며, 알 수 없는 판별자는
`ERROR(INVALID_ACTION)` 으로 회신된다.

| @action | 추가 필드 | 비고 |
| --- | --- | --- |
| `DECLARE_GRAND_TICHU` | — | Dealing(phaseCardCount=8), 아직 ready 아닐 때만 |
| `DECLARE_TICHU` | — | Dealing(14) 또는 Playing 첫 플레이 전까지 |
| `READY` | — | Dealing 단계 "다음 단계로" 신호 (Phase 5b) |
| `PASS_CARDS` | `toLeft, toPartner, toRight` (각 Card) | Passing 단계, 좌석당 1회 |
| `PLAY_CARD` | `cards: Card[]` | 일반 플레이 (Phoenix 해석은 서버가 결정) |
| `PASS_TRICK` | — | 트릭 패스 (리드 차례에는 불가) |
| `MAKE_WISH` | `rank: 2..14` | Mahjong 을 낸 직후만 |
| `GIVE_DRAGON_TRICK` | `toSeat` | Dragon 트릭 획득 직후 — 상대팀 좌석 |

`Card` 직렬화 (서버 `Card` record 와 동일):
```json
{ "suit": "JADE", "rank": 9,  "special": null }
{ "suit": null,   "rank": 0,  "special": "PHOENIX" }
```

### 액션 처리 단계 (서버)
1. 방 조회 → 좌석 도출(비참가자는 `ERROR(NOT_IN_ROOM)`), `gameType` 으로 엔진 획득
   (`GameEngineProvider.forRoom`).
2. `engine.actionType()` 으로 payload 역직렬화 — 실패 시 `ERROR(INVALID_ACTION)`.
3. 방 락 획득 (`SET NX room:{id}:lock TTL=2s`) — 실패 시 본인 큐 `ERROR(BUSY)`.
4. `engine.loadState()` (`room:{id}:state` JSON 역직렬화). 없으면 `ERROR(GAME_NOT_STARTED)`.
5. `engine.apply(state, seat, action)` — 룰 위반 시 `GameActionRejectedException.code()`
   를 그대로 본인 큐 `ERROR` 코드로, 락 해제.
6. 새 상태 저장, `engine.advance(...)` 로 라운드/매치 진행 이벤트 합류,
   이벤트마다 `room:{id}:seq` INCR, envelope 을 공개/비공개로 분기 전송.
7. 락 해제 후 봇 스케줄(`BotScheduler`)·턴 타임아웃(`TurnTimeoutScheduler`) 트리거.

---

## 보안 검토 체크리스트

- [x] 본인 큐 페이로드가 토픽으로 발행되지 않는지 통합 테스트로 검증.
- [x] 비참가자 액션은 `NOT_IN_ROOM` 으로 거부(공개 토픽에는 공개 정보만 흐른다 —
      State Hiding 은 발행 시점에 강제).
- [x] 클라가 보낸 어떤 `seq` 도 서버는 신뢰하지 않고 자체 카운터를 사용.
