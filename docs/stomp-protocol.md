# Mirboard WebSocket / STOMP 프로토콜 (Phase 1)

## 연결

- 엔드포인트: `ws://<host>/ws` (Spring STOMP + SockJS fallback)
- 헤더: `Authorization: Bearer <JWT>` — `ChannelInterceptor` 가 CONNECT 단계에서
  검증, 실패 시 `ERROR` 프레임 후 연결 종료.
- 클라는 CONNECT 직후 자기 큐 `/user/queue/...` 와 필요한 토픽을 구독한다.

## 토픽 / 큐 카탈로그

| 종류 | 목적지 | 발행 주체 | 가시성 |
| --- | --- | --- | --- |
| TOPIC | `/topic/lobby/chat` | 서버 | 로그인 사용자 전체 |
| TOPIC | `/topic/lobby/rooms` | 서버 | 로그인 사용자 전체 (방 목록 변경) |
| TOPIC | `/topic/room/{roomId}` | 서버 | 해당 방 참가자 (공개 이벤트) |
| TOPIC | `/topic/room/{roomId}/chat` | 서버 | 해당 방 참가자 + 관전자 (인-게임 채팅, Phase 8B) |
| QUEUE | `/user/queue/room/{roomId}` | 서버 | 본인만 (손패/에러) |
| QUEUE | `/user/queue/errors` | 서버 | 본인 (CONNECT 직후 일반 에러) |
| APP   | `/app/lobby/chat` | 클라 | 서버 |
| APP   | `/app/room/{roomId}/chat` | 클라 | 서버 |
| APP   | `/app/room/{roomId}/action` | 클라 | 서버 |

> 클라이언트는 `/topic/room/{roomId}` 의 비참가자 구독을 시도해도 서버가
> `ChannelInterceptor` 단계에서 권한 차단(`FORBIDDEN`)한다.

## Envelope (모든 STOMP 메시지 공통)

```json
{
  "eventId": "9d6f-...",     // 서버가 생성 (멱등 처리용 키)
  "seq":     142,             // 방 단위 단조 증가 (room:{id}:seq INCR), 클라→서버 시 0
  "type":    "PLAYED",
  "ts":      1715600000000,
  "payload": { ... }
}
```

- `seq` 는 서버→클라 이벤트에서만 의미가 있다. 클라는 `seq <= localSeq` 인 이벤트를
  무시(idempotent). 재접속 후 REST `/resync` 로 최신 상태를 받은 뒤 그보다 큰
  이벤트만 적용.
- `eventId` 는 같은 이벤트가 두 번 전송될 때 dedup 용도.
- **Phase 5d**: 클라이언트는 reducer 가 정의된 이벤트(PLAYED, PASSED, TURN_CHANGED,
  TRICK_TAKEN, PLAYER_FINISHED, TICHU_DECLARED, WISH_MADE, DRAGON_GIVEN, PLAYER_READY,
  PASSING_SUBMITTED, ROUND_ENDED, MATCH_ENDED) 를 store 에 부분 패치로 직접 반영하고,
  라이프사이클 이벤트(DEALING_PHASE_STARTED, PASSING_STARTED, CARDS_PASSED,
  PLAYING_STARTED, ROUND_STARTED) 또는 seq gap (`seq > lastSeq + 1`) 에서만 `/resync`
  로 권위 있는 스냅샷을 재취득한다. 초기 mount 및 STOMP onConnect 직후 `/resync` 는
  유지.

---

## 서버 → 클라 (공개) — `/topic/room/{roomId}`

| type | payload 핵심 필드 | 의미 |
| --- | --- | --- |
| `ROOM_UPDATED` | `{ room }` | 방 메타 변경 (입퇴장, 호스트 변경 등) |
| `GAME_STARTED` | `{ dealerUserId, teams: { A:[u1,u3], B:[u2,u4] } }` | 라운드 시작 직전 |
| `DEALING_PHASE_STARTED` | `{ phaseCardCount: 8\|14 }` | Dealing 단계 시작/전환 (Phase 5b) |
| `PLAYER_READY` | `{ seat }` | 좌석이 Dealing 윈도우 결정을 마침 |
| `PASSING_STARTED` | `{}` | Dealing(14) 마감, 카드 패스 단계 진입 |
| `PASSING_SUBMITTED` | `{ seat }` | 좌석의 패스 카드 제출 완료 |
| `CARDS_PASSED` | `{}` | 4명 모두 제출 후 동시 스왑 완료 (실제 카드는 비공개 큐로) |
| `PLAYING_STARTED` | `{ leadSeat }` | Playing 단계 진입, Mahjong 보유자 리드 |
| `ROUND_STARTED` | `{ roundNumber, cumulativeScores: {A,B} }` | 새 라운드 시작 (Phase 5c, RoundEnded 직후 같이 발행) |
| `MATCH_ENDED` | `{ winningTeam, finalScores: {A,B}, roundsPlayed }` | 매치 종료 (한 팀 ≥1000 + 점수 다름) |
| `HAND_DEALT_COUNT` | `{ counts: {userId: int} }` | 손패 장수 공개 갱신 |
| `TURN_CHANGED` | `{ currentTurnSeat, expiresAt }` | 턴 전환 |
| `PLAYED` | `{ userId, hand: { type, cards } }` | 카드 플레이 결과 |
| `PASSED` | `{ userId }` | 패스 |
| `TRICK_TAKEN` | `{ takerUserId, trickPoints }` | 트릭 종료/획득 |
| `WISH_MADE` | `{ rank }` | Mahjong 소원 |
| `WISH_FULFILLED` | `{}` | 소원 해소 |
| `TICHU_DECLARED` | `{ userId, kind: "TICHU"\|"GRAND" }` | 선언 알림 |
| `BOMB_USED` | `{ userId }` | 폭탄 인터럽트 알림 (선택) |
| `DRAGON_GIVEN` | `{ fromUserId, toUserId }` | 드래곤 트릭 양도 결과 |
| `ROUND_ENDED` | `{ teamAScore, teamBScore, breakdown }` | 라운드 점수 |
| `GAME_ENDED` | `{ winningTeam, finalScores, matchId }` | 게임 종료 + 전적 ID |
| `PLAYER_DISCONNECTED` | `{ userId }` | 연결 끊김 |
| `PLAYER_RECONNECTED` | `{ userId }` | 재접속 완료 |
| `CHAT` | `{ userId, message }` | 방 채팅 |

---

## 서버 → 클라 (비공개) — `/user/queue/room/{roomId}`

| type | payload | 의미 |
| --- | --- | --- |
| `HAND_DEALT` | `{ seat, cards, phaseCardCount: 8\|14 }` | 8장 (Dealing 진입) 또는 14장 (전환/스왑 후) 손패. Phase 5b 에선 한 이벤트 타입으로 통합되고 `phaseCardCount` 로 의미 구분. |
| `RESYNC` | `{ tableView, privateHand, phase, eventSeq }` | 재접속 직후 |
| `ERROR` | `{ code, message }` | 본인의 잘못된 액션 |

> 본인 큐의 메시지는 절대 `/topic` 으로 누출되어선 안 된다. 서버 측 직렬화 시
> `PrivateHand` 와 `TableView` DTO는 분리된 타입으로 다룬다.

---

## 클라 → 서버 — `/app/room/{roomId}/action`

공통 envelope를 보내며 `type` 으로 구분한다. 잘못된 액션은 본인 큐 `ERROR` 로만
회신되고 다른 플레이어 상태는 변하지 않는다.

| type | payload | 비고 |
| --- | --- | --- |
| `DECLARE_GRAND_TICHU` | `{}` | Dealing(phaseCardCount=8) 단계, 아직 ready 가 아닐 때만 |
| `DECLARE_TICHU` | `{}` | Dealing(phaseCardCount=14) 또는 Playing 첫 플레이 전까지 |
| `READY` | `{}` | Dealing 단계에서 "선언 없이 다음 단계로" 신호 (Phase 5b) |
| `PASS_CARDS` | `{ toLeft: cardRef, toPartner: cardRef, toRight: cardRef }` | Passing 단계, 좌석당 1회 |
| `PLAY_CARD` | `{ cards: cardRef[], phoenixAs?: cardRef }` | 일반 플레이 |
| `PASS_TRICK` | `{}` | 트릭 패스 (리드 차례에는 불가) |
| `MAKE_WISH` | `{ rank: 2..14 }` | Mahjong을 낸 직후만 |
| `GIVE_DRAGON_TRICK` | `{ toSeat }` | Dragon으로 트릭 가져간 직후 — 상대팀 좌석 |

`cardRef`
```json
{ "suit": "JADE",  "rank": 9 }
{ "special": "PHOENIX" }
```

### 액션 처리 단계 (서버)
1. 방 락 획득 (`SET NX room:{id}:lock TTL=2s`).
2. `room:{id}:state` 로드 (JSON 역직렬화).
3. `ActionValidator` 통과 여부 — 실패 시 본인 큐 `ERROR`, 락 해제.
4. `GameEngine.apply(state, action)` → `(newState, events[])`.
5. 새 상태 저장, `room:{id}:seq` INCR, 이벤트 envelope을 공개/비공개로 분기 전송.
6. 락 해제.

---

## 보안 검토 체크리스트

- [ ] 본인 큐 페이로드가 토픽으로 발행되지 않는지 단위 테스트로 검증.
- [ ] 비참가자가 `/topic/room/{id}` 또는 `/user/queue/room/{id}` 구독을 시도하면
      서버가 차단하는지 확인.
- [ ] 클라가 임의로 `seq` 를 위조한 envelope을 보내도 서버는 `seq` 를 신뢰하지
      않고 자체 카운터를 사용.
