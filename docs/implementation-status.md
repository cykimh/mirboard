# Mirboard 구현 현황

> 지금까지 **실제로 구현된 기능**을 end-to-end로 정리한 현황 문서.
> 구조/흐름은 `docs/architecture.md`, 의사결정 이력은 `docs/decisions.md`(D-01~D-77),
> 단계별 진행은 `docs/plans/mvp-roadmap.md` 참조.
> 기능 설명의 세부 계약은 `docs/api.md`(REST), `docs/stomp-protocol.md`(STOMP),
> `docs/rules-tichu.md`(룰)가 정본이다.

---

## 0. 한눈에 보기

플랫폼은 **동작하는 MVP** 상태다. 로비/방 → 티츄 풀게임(특수 카드 포함) → 점수·ELO
영속 → 봇 자동 채움 → 재접속/탈주 처리 → UI(라이트/다크) 까지 end-to-end로 연결되어 있다.

| # | 기능 | 상태 | 핵심 위치 |
|---|------|------|-----------|
| 1 | 회원가입 / 로그인 / 프로필 (JWT) | ✅ | `domain.lobby.auth`, `infra.rest.auth`, `infra.rest.me` |
| 2 | 게임 카탈로그 (확장형) | ✅ | `domain.game.core.GameRegistry`, `infra.rest.games` |
| 3 | 방 생성/입장/준비/퇴장/관전 | ✅ | `domain.lobby.room`, `infra.rest.rooms`, `lua/room_*.lua` |
| 4 | WebSocket/STOMP 실시간 | ✅ | `infra.ws`, `infra.config.WebSocketConfig` |
| 5 | 티츄 룰 엔진 (전 페이즈 + 특수 카드) | ✅ | `domain.game.tichu` |
| 6 | 봇 플레이어 (빈 좌석 자동 채움) | ✅ | `infra.bot`, `domain.game.tichu.bot` |
| 7 | 재접속 동기화 (resync) | ✅ | `RoomService`, `GET /rooms/{id}/resync` |
| 8 | 탈주/끊김 처리 (유예→패널티) | ✅ | `infra.ws` 탈주 핸들러, `DesertionService` |
| 9 | 랭킹 / ELO | ✅ | `domain.game.scoring.EloCalculator`, `infra.rest.users` |
| 10 | 채팅 (로비 / 방) | ✅ | `infra.ws.lobby`, `infra.ws.RoomChatController` |
| 11 | UI 리디자인 (Tailwind+shadcn, 다크) | ✅ | `client` (Phase 20, D-76/77) |
| 12 | 멀티 인스턴스 (opt-in) | ✅(opt-in) | `infra.messaging.RedisMessageGateway` |
| 13 | 관측성 (Prometheus, MDC 로그) | ✅ | `infra.metrics`, `infra.web.MdcKeys` |

설계 단계(Phase 1)부터 클라이언트 통합·UI 리디자인(Phase 20)까지 로드맵 항목이 완료
표시되어 있다(`docs/plans/mvp-roadmap.md`).

---

## 1. 인증 (Phase 2b)

- **회원가입** `POST /api/auth/register` — username(영숫자/언더스코어 3~20자) +
  password(8~64자). BCrypt 해시로 `users.password_hash` 저장.
- **로그인** `POST /api/auth/login` — JWT(HS256, 12h) 발급. 시크릿 `MIRBOARD_JWT_SECRET`.
- **프로필** `GET /api/me` — userId, username, win/lose, rating, tier(derived), desert_count.
- 인증 경로: REST는 `JwtAuthFilter`(Authorization: Bearer), STOMP는 CONNECT 헤더 검증.
- 개인정보 최소화 원칙상 이메일/전화 등은 수집·저장하지 않는다(`docs/architecture.md` §2.3).

관련 테스트: `AuthServiceTest`, `JwtServiceTest`, `AuthFlowIntegrationTest`.

---

## 2. 게임 카탈로그 (Phase 2c)

- `GET /api/games` — 등록된 `GameDefinition` 목록(상태순 정렬). 현재 **TICHU(AVAILABLE)**.
- `GET /api/games/{id}` — 단일 게임 상세.
- **확장성**: 새 게임은 `domain.game.{newgame}` + `GameDefinition @Component` 등록만으로
  카탈로그·방 생성·엔진 디스패치에 자동 연결(REST/로비 코드 무변경).

관련 테스트: `GameRegistryTest`, `GameCatalogIntegrationTest`.

---

## 3. 방 관리 (Phase 2d, 16, 18)

- **생성** `POST /api/rooms` {name, gameType} — 생성자가 호스트. 정원은
  `GameDefinition.maxPlayers()`(티츄 4). Redis `room:{id}` + `room:{id}:players` 저장.
- **목록** `GET /api/rooms?gameType=&status=` — status로 필터.
- **입장** `POST /api/rooms/{id}/join` — `room_join.lua` 원자 검증(정원/멤버십).
  **capacity 자동시작 폐기**(Phase 16): 입장만으로는 시작하지 않는다.
- **준비** `POST /api/rooms/{id}/ready` — `room_ready.lua` 로 ready SET 토글. **정원 4 +
  전원 ready** 시에만 WAITING→IN_GAME 원자 전이(봇은 join 시 서버가 자동 ready).
- **퇴장** `POST /api/rooms/{id}/leave` — WAITING은 즉시 퇴장, IN_GAME은 탈주 처리(§8).
  빈 방은 `room_leave.lua`/`room_delete.lua` 가 관련 키까지 정리.
- **재입장(멱등)** `POST /api/rooms/{id}/join-or-reconnect` — JOINED/RECONNECTED/SPECTATING
  모드 반환. 나갔다 재입장 시 ALREADY_IN_ROOM 버그 제거(Phase 18, D-74).
- **팀 정책** `PUT /api/rooms/{id}/team-policy` (SEQUENTIAL/RANDOM/MANUAL, 호스트만).
- **중단** `POST /api/rooms/{id}/abort` (호스트, in-game → FINISHED).
- **관전** `POST`/`DELETE /api/rooms/{id}/spectate` — 관전자는 손패·액션 불가(§상태 은닉).
- **재동기화** `GET /api/rooms/{id}/resync` — tableView + privateHand + seq(§7).

동시성 보장: capacity 위반 0건(`RoomServiceConcurrencyIT`, 멀티스레드 join).
관련 테스트: `RoomControllerIntegrationTest`, `RoomResyncIntegrationTest`.

---

## 4. WebSocket / STOMP (Phase 2e~)

- 엔드포인트 `ws://host/ws` (STOMP + SockJS), CONNECT JWT 인증.
- **공개 토픽**: `/topic/lobby/chat`, `/topic/lobby/rooms`, `/topic/room/{id}`, `/topic/room/{id}/chat`.
- **비공개 큐**: `/user/queue/room/{id}` (HAND_DEALT, RESYNC, ERROR).
- **클라→서버**: `/app/room/{id}/action`, `/app/lobby/chat`, `/app/room/{id}/chat`.
- 모든 메시지는 `{ eventId, seq, type, ts, payload }` envelope. 서버는 클라가 보낸 `seq`를
  무시하고 `room:{id}:seq` INCR 값을 권위 카운터로 사용.

처리 흐름과 이벤트 카탈로그는 `docs/architecture.md` §4.2 / `docs/stomp-protocol.md`.
관련 테스트: `GameStompControllerIntegrationTest`, `StompLobbyIntegrationTest`.

---

## 5. 티츄 룰 엔진 (Phase 3~6)

서버-권위 무상태 엔진: `TichuEngine.apply(state, seat, action) → (newState, events[])`.
상태는 sealed `TichuState`(Dealing → Passing → Playing → RoundEnd)로 전이.

### 5.1 라운드 라이프사이클
- **Dealing(8)**: 셔플 후 8장 분배. **그랜드 티츄** 선언 가능(±200), 또는 Ready로 스킵.
  전원 진행 시 Dealing(14)로.
- **Dealing(14)**: 나머지 6장 분배. **티츄** 선언 가능(±100), 또는 Ready로 스킵 → Passing.
- **Passing**: 각자 3장 교환(왼/파트너/오른). 전원 제출 시 동시 스왑 → Playing.
- **Playing**: 마작(MAHJONG) 보유자 선두. 족보 비교로 트릭 진행, 전원 패스 시 트릭 종료.
- **RoundEnd**: 트릭 점수 + 보너스 + 티츄/그랜드 가감 집계. 목표 점수 도달 시 매치 종료.

### 5.2 액션 (sealed `TichuAction`)
`DeclareGrandTichu`, `DeclareTichu`, `Ready`, `PassCards`, `PlayCard`(phoenixAs override),
`PassTrick`, `MakeWish`, `GiveDragonTrick`. 페이즈별 검증은 `ActionValidator`,
소원 충족 판정은 `WishFulfillmentChecker`.

### 5.3 카드 / 족보 / 특수 카드
- 카드 모델 `Card`(일반 suit+rank / 특수 MAHJONG·DOG·PHOENIX·DRAGON), `Deck` 셔플.
- 족보 판별 `HandDetector`, 비교 `HandComparator`(sealed `Hand`). 폭탄(BOMB)은 트릭 인터럽트.
- 특수 카드: **마작**(소원 호출), **피닉스**(와일드, 트릭 획득 시 −25), **드래곤**(최강 단일,
  +25, 트릭을 상대팀에 넘김), **개**(다음 차례 강제).
- 마작 플레이 → MAKE_WISH 흐름, 드래곤 트릭 → GIVE_DRAGON_TRICK 흐름이 클라 모달과 연동
  (`MakeWishModal`, `GiveDragonTrickModal`).

> 족보 종류/점수 규칙/특수 카드 상호작용의 정확한 정의는 `docs/rules-tichu.md` 가 정본.

### 5.4 점수 / 영속
- `ScoreCalculator` → `RoundScore`(팀 A/B + 내역). 누적은 `TichuMatchState`.
- 매치 종료 시 `MatchResultRecorder` 가 `tichu_match_results`/`tichu_match_participants`
  영속 + ELO 갱신.

관련 테스트: `HandDetectorTest`, `TichuEngineRoundSimulationTest`, `DealingLifecycleTest`,
특수 카드 시나리오, `TurnManagerTest`, `MatchResultRecorderIT`.

---

## 6. 봇 플레이어 (Phase 9A, D-49)

- 시드 봇 4명(`bot_north/east/south/west`, `is_bot=TRUE`, 로그인 불가) — V3 마이그레이션.
- 사람 < 4명으로 시작 시 빈 좌석을 봇으로 채움(join 시 서버가 자동 ready).
- `infra.bot.BotScheduler` 가 봇 차례에 `LegalActionEnumerator` 의 합법 수 중 선택해 적용.
  딜레이 `mirboard.bot.delay-millis`(기본 700ms, D-76 후속 상향).
- 봇 라벨은 UI에서 제거(D-76 후속), 봇 매치는 ELO 집계 제외(D-71).
- `TurnTimeoutScheduler` 가 무응답 차례를 자동 처리.

---

## 7. 재접속 동기화 (Phase 8A)

- 새로고침/탭 복귀 시 WS 자동 재연결(동일 JWT) → `GET /api/rooms/{id}/resync` 로
  tableView + privateHand + seq 수신 → 토픽/큐 재구독.
- `seq` 갭으로 누락 판단, 필요 시 전체 resync(RESYNC 이벤트)로 복구.
- 손패는 항상 `/user/queue` 로만 복원(공개 토픽 누출 금지).

관련 테스트: `RoomResyncIntegrationTest`, `RoomJoinOrReconnectIntegrationTest`.

---

## 8. 탈주 / 끊김 처리 (Phase 19, D-75)

- 서버가 STOMP Subscribe/Disconnect 후킹(in-memory `WsSessionRegistry`, 단일 인스턴스 전제).
- **WAITING 끊김** = 즉시 leave(빈 방·관전자 0 방 즉시 소멸).
- **IN_GAME 탈주** = 명시 '나가기' 또는 끊김 후 `mirboard.desertion.grace-seconds`(기본 30s)
  미복귀 → **상대팀 승리**로 매치 종료 + 탈주자 `desert_count`+1·lose+1·ELO−
  (봇 매치 ELO 제외). 합성 `TichuMatchCompleted` 로 기존 `MatchResultRecorder` 재사용.
- 유예 내 복귀 시 세션 복원.

관련 클래스/테스트: `DesertionService`, 끊김/탈주 유닛 + `MatchResultRecorder` 탈주 IT.

---

## 9. 랭킹 / ELO (Phase 8D)

- `users.rating`(기본 1000) 를 ELO(K=32, `EloCalculator`)로 매치 결과마다 갱신. 봇 제외.
- **tier** 는 컬럼이 아니라 rating 구간에서 계산(BRONZE→…→MASTER, derived).
- 엔드포인트: `GET /api/users/{id}/stats`, `GET /api/users/ranking`(봇 제외 상위), `GET /api/me`.

관련 테스트: `EloCalculatorTest`, 사용자 통계 통합 테스트.

---

## 10. 채팅 (Phase 8B)

- **로비(전역) 채팅**: `/app/lobby/chat` → `/topic/lobby/chat` (메인=미르보드카페 전역).
- **방 채팅**: `/app/room/{id}/chat` → `/topic/room/{id}/chat` (`RoomChatController`).
- 클라: `RoomChat`, `ArenaChatBubbles`, `roomChatStore`(FIFO 버퍼).
- 게임별 격리 채팅은 MVP 범위 밖.

---

## 11. 클라이언트 / UI (Phase 4d~, Phase 20)

- 페이지: `LoginPage`, `RegisterPage`, `GameHubPage`(메인 — 방 생성/입장/관전/랭킹/로비채팅
  통합), `RoomPage`(대기실 + 게임판).
- 게임판: `GameTable`, `SortableHand`(@dnd-kit 손패 정렬), `CardChip`, `SeatAvatar`,
  `EffectsOverlay`, 소원/드래곤 모달.
- 상태: Zustand 스토어(`authStore`, `tichuStore`, `roomChatStore`, `themeStore`, `effectStore`).
- **Phase 20(D-76/77)**: Tailwind v3(`preflight:false`) + shadcn/ui(slate, CSS vars),
  라이트/다크 토글(`themeStore`, `<html>.dark`, 기본 dark). 게임판 기하는 `styles.css` 유지,
  좌석 컴팩트화·경기장 확대 등 후속 개선.

관련 테스트(Vitest): `tichuStore.*`, `authStore`, `roomChatStore`, `effectStore`, `handType` 등.

---

## 12. 운영 / 인프라 기능

- **멀티 인스턴스(opt-in)**: `mirboard.messaging.gateway=redis` 시 Redis Pub/Sub fan-out
  (`RedisMessageGateway`, `DomainEventBus` instanceId 중복 제거). 기본은 in-memory 단일 인스턴스.
- **관측성**: Prometheus(`/actuator/prometheus`, `MirboardMetrics`), MDC 로그
  (`userId`/`roomId`/`eventId`, `MdcKeys`).
- **배포**: `client` 빌드를 서버 정적 리소스로 번들해 단일 jar 서빙, `Dockerfile`/`fly.toml`
  멀티스테이지로 Fly.io.

---

## 13. 테스트 현황

- **서버**: 44개 테스트 클래스. 단위(룰 엔진·족보·ELO·JWT·카탈로그) + 통합
  (Testcontainers PostgreSQL 16/Redis — auth/rooms/STOMP/봇/동시성/매치 영속).
- **클라이언트**: Vitest + RTL — 스토어 리듀서, 족보 타입, 카드 에셋 매핑 등.
- 통합 테스트는 Docker 필요. 실행 명령은 `CLAUDE.md` "자주 쓰는 명령" 참조.

---

## 14. 미구현 / 범위 밖 (참고)

- 게임별 격리 채팅(로비/방 채팅만 존재).
- 티츄 외 게임(카탈로그는 확장 가능하나 등록된 게임은 TICHU 1종).
- JWT 리프레시 토큰(12h 단일 토큰, MVP 범위).
- 멀티 인스턴스 세션 레지스트리(`WsSessionRegistry` 는 단일 인스턴스 전제).

> 최신 결정/번복은 `docs/decisions.md`, 진행 단계는 `docs/plans/mvp-roadmap.md` 가 정본.
> 본 문서와 어긋날 경우 그쪽을 신뢰한다.

---

*문서 생성: 코드베이스 정적 분석 기반(서버 126 클래스 / 테스트 44 클래스, 마이그레이션 V1~V4 확인).*
