# Mirboard 구현 현황

> 지금까지 **실제로 구현된 기능**을 end-to-end로 정리한 현황 문서.
> 구조/흐름은 `docs/architecture.md`, 의사결정 이력은 `docs/decisions.md`(D-01~D-105),
> 단계별 진행은 `docs/plans/mvp-roadmap.md` 참조.
> 기능 설명의 세부 계약은 `docs/api.md`(REST), `docs/stomp-protocol.md`(STOMP),
> `docs/game-port.md`(`GameEngine` 포트), `docs/rules-tichu.md`·`docs/rules-skullking.md`(룰)가
> 정본이다.

---

## 0. 한눈에 보기

플랫폼은 **동작하는 MVP** 상태다. 로비/방 → **게임 2종**(티츄 4인 2:2 팀전 / 스컬킹
2~8인 개인전) 풀게임 → 점수·ELO 영속(티츄) → 봇 자동 채움 → 재접속/탈주 처리 →
UI(라이트/다크) 까지 end-to-end로 연결되어 있다.

| # | 기능 | 상태 | 핵심 위치 |
|---|------|------|-----------|
| 1 | 회원가입 / 로그인 / 프로필 (JWT) | ✅ | `domain.lobby.auth`, `infra.rest.auth`, `infra.rest.me` |
| 2 | 게임 카탈로그 (확장형) | ✅ | `domain.game.core.GameRegistry`, `infra.rest.games` |
| 3 | 방 생성/입장/준비/퇴장/관전 | ✅ | `domain.lobby.room`, `infra.rest.rooms`, `lua/room_*.lua` |
| 4 | WebSocket/STOMP 실시간 | ✅ | `infra.ws`, `infra.config.WebSocketConfig` |
| 5 | 티츄 룰 엔진 (전 페이즈 + 특수 카드) | ✅ | `domain.game.tichu` |
| 5b | 스컬킹 (룰 엔진 + 배선 + 클라 게임판) | ✅ | `domain.game.skullking`, `features/skullking` (§16) |
| 6 | 봇 플레이어 (빈 좌석 자동 채움) | ✅ | `infra.bot`, `domain.game.tichu.bot` |
| 7 | 재접속 동기화 (resync) | ✅ | `RoomService`, `GET /rooms/{id}/resync` |
| 8 | 탈주/끊김 처리 (유예→패널티) | ✅ | `infra.ws` 탈주 핸들러, `DesertionService` |
| 9 | 랭킹 / ELO | ✅ | `domain.game.scoring.EloCalculator`, `infra.rest.users` |
| 10 | 채팅 (로비 / 방) | ✅ | `infra.ws.lobby`, `infra.ws.RoomChatController` |
| 11 | UI 리디자인 (Tailwind+shadcn, 다크) | ✅ | `client` (Phase 20, D-76/77) |
| 12 | 멀티 인스턴스 (opt-in) | ✅(opt-in) | `infra.messaging.RedisMessageGateway` |
| 13 | 관측성 (Prometheus, MDC 로그) | ✅ | `infra.metrics`, `infra.web.MdcKeys` |
| 14 | **`GameEngine` 포트 (멀티게임 기반)** | ✅ | `domain.game.core.GameEngine`, `infra.ws.GameEngineProvider`, `TichuGameEngine` |

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

- `GET /api/games` — 등록된 `GameDefinition` 목록(상태순 정렬). 현재 **TICHU**·**SKULL_KING**
  둘 다 AVAILABLE.
- `GET /api/games/{id}` — 단일 게임 상세.
- **확장성**: 새 게임은 `domain.game.{newgame}` + `GameDefinition @Component` 등록만으로
  카탈로그·방 생성·엔진 디스패치에 자동 연결(REST/로비 코드 무변경).
  D-98 이후 이 문장은 **인게임까지 참**이다 — §14 참조.

관련 테스트: `GameRegistryTest`, `GameCatalogIntegrationTest`.

---

## 3. 방 관리 (Phase 2d, 16, 18)

- **생성** `POST /api/rooms` {name, gameType} — 생성자가 호스트. 정원은 선택 필드
  `capacity`(D-99), 생략하면 `GameDefinition.maxPlayers()`. 게임이 정한
  `minPlayers()..maxPlayers()` 밖이면 `INVALID_CAPACITY` — 티츄는 4~4 라 무변경.
  Redis `room:{id}` + `room:{id}:players` 저장.
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
관련 테스트: `RoomControllerIntegrationTest`, `RoomResyncIntegrationTest`,
`RoomCapacityIntegrationTest`(D-99 인원 가변 — 가변 게임은 테스트용 fake `GameDefinition`).

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

## 8. 탈주 / 끊김 처리 (Phase 19 D-75, 확장 D-96·D-104)

- 서버가 STOMP Subscribe/Disconnect 를 후킹한다. 프레즌스는 **Redis `RoomPresence`**
  (세션 카운터 HASH) — D-75 의 in-memory `WsSessionRegistry` 는 D-96 에서 대체됐다.
  in-memory 로는 인스턴스 A 에 붙은 재접속을 B 가 못 봐서 "재접속했는데 탈주 처리"가 난다.
- **WAITING 끊김** = 즉시 leave(빈 방·관전자 0 방 즉시 소멸).
- **IN_GAME 탈주** = 명시 '나가기' 또는 끊김 후 `mirboard.desertion.grace-seconds`
  (**기본 120s**, D-79 에서 모바일 백그라운드 전환을 감안해 30s→120s) 미복귀.
  처리는 게임이 정한다 — 포트가 3치 `DesertOutcome` 를 돌려준다(D-102/D-104):
  - **티츄**(2:2 팀전) → `MATCH_ENDED`. 상대팀 승리로 매치 종료. 합성
    `TichuMatchCompleted` 로 기존 `MatchResultRecorder` 재사용.
  - **스컬킹**(개인전) → `MATCH_CONTINUES`. 좌석을 지우지 않고 **유령 좌석 + 자동조종**
    으로 남은 사람끼리 계속한다(`seatCount` 가 트릭 크기·턴 모듈러·카드 보존 불변식에
    동시에 묶여 있어 좌석 제거가 불가). 잔존 좌석 < 2 이거나 잔존 사람 0 이면 조기 종료.
- 탈주자 패널티(`desert_count`+1·lose+1·ELO−, 봇 매치 ELO 제외)는 영속이 있는
  티츄에만 적용된다 — 스컬킹 매치 영속은 D-02 게임별 rating 분리 선행으로 별건.
- 유예 내 복귀 시 세션 복원.

관련 클래스/테스트: `DesertionService`, `RoomPresence`, `SkullKingDesertionTest`,
끊김/탈주 유닛 + `MatchResultRecorder` 탈주 IT.

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
  멀티스테이지로 Fly.io. CD 는 `.github/workflows/deploy.yml`(main 푸시 + 수동 트리거) —
  `FLY_API_TOKEN` 미설정이면 잡이 스스로 건너뛴다. 라이브 인스턴스는 아직 없다(D-105).
- **데모 계정**: `DemoAccountSeeder`, `mirboard.demo.enabled` **기본 false**. 공개 비밀번호
  계정이 로컬·CI 에 생기지 않도록 마이그레이션이 아닌 환경변수 게이트 시더로 둔다(D-105).

---

## 13. 테스트 현황

- **서버**: **733건** (D-105 시점 실측, 실패 0). 스컬킹은 순수 305건 + 통합(JSON 왕복·봇 풀매치 IT) 포함.
  단위(룰 엔진·족보·ELO·JWT·카탈로그·포트 어댑터) + 통합(Testcontainers PostgreSQL 16/
  Redis — auth/rooms/STOMP/봇/동시성/매치 영속/2-인스턴스 인계).
- 스컬킹 305건은 **전부 Docker 불필요** — 순수 룰 엔진이라 `./scripts/check.sh rules` 에
  묶여 있다(티츄 룰 단위와 함께 ~5s).
- **클라이언트**: Vitest + RTL — 스토어 리듀서, 족보 타입, 카드 에셋 매핑 등.
- 통합 테스트는 Docker 필요. 실행 명령은 `CLAUDE.md` "자주 쓰는 명령" 참조.

---

## 14. `GameEngine` 포트 (멀티게임 기반, D-98)

인게임 진행이 게임별 타입 대신 **포트 뒤**에서 돌아간다. 계약 정본은 `docs/game-port.md`.

- **포트**: `domain.game.core.GameEngine` — 상태 I/O, 액션 적용, 단계 이름, 대기 좌석,
  공개/비공개 뷰, 합법 액션·봇·타임아웃 액션, 라운드/매치 진행(`advance`)·탈주(`desert`).
  `GameState`/`GameAction` 은 마커, `GameEvent` 는 `envelopeType()`+`privateSeat()` 만 노출.
- **디스패치**: `infra.ws.GameEngineProvider.forRoom(room)` →
  `GameRegistry.require(gameType).newEngine(ctx)`. 인프라 코드에 게임 이름이 없다.
- **티츄 구현은 2계층**: 순수 룰 엔진 `TichuEngine`(저장소 없음) + 포트 어댑터
  `TichuGameEngine`. 룰 단위 테스트가 Redis 를 끌고 오지 않게 하는 분리.
- **액션 역직렬화**: 목적지(`/app/room/{id}/action`)는 하나이고 방의 `gameType` 으로
  `engine.actionType()` 을 골라 변환한다. 알 수 없는 액션은 `ERROR(INVALID_ACTION)`.
- **알려진 잔여**: `infra.ws.RoomChipService` 만 티츄를 직접 참조한다(칩 정산이 팀 승패에
  묶여 있고 칩은 의도적으로 포트 밖 — `docs/game-port.md` §2).

관련 테스트: `TichuGameEngineDesertionTest`, `TichuGameEnginePendingSeatsTest`,
`GameStompControllerIntegrationTest`(실제 WS 프레임), `BotMatchSimulationIT`,
`TurnTimeoutSchedulerIT`.

---

## 15. 미구현 / 범위 밖 (참고)

- 게임별 격리 채팅(로비/방 채팅만 존재).
- 스컬킹 매치 결과 영속·ELO — D-02 의 게임별 rating 분리 결정이 선행(D-102 보류).
- 스컬킹 손패 dnd 정렬·특수 카드 SVG 에셋·i18n 이관 — S6 범위 밖(D-103 이월).
- JWT 리프레시 토큰(12h 단일 토큰, MVP 범위).
- Sentry/Grafana 관측성(M2 C3) — 로컬 스택 또는 외부 SaaS 필요.
- 라이브 배포 — CD 워크플로는 준비됐고 `FLY_API_TOKEN` 설정만 남았다(D-105).
  (멀티 인스턴스 세션 레지스트리는 D-96 에서 해소 — §8 참조.)

---

## 16. 스컬킹 (S4 룰 엔진 + S5 통합 + S6 클라, D-101·D-104·D-102·D-103)

`domain.game.skullking` 에 **룰 엔진·인게임 배선·클라 게임판까지** 구현됐다. 룰 정본은
`docs/rules-skullking.md`(절마다 `코드:`/`테스트:` 로 코드와 1:1 매핑).

- **패키지**: `card/`(70장 덱) · `state/`(sealed `SkullKingState`: Bidding/Playing/RoundEnd)
  · `trick/`(`LeadSuitResolver`·`TrickResolver`) · `bid/` · `action/` · `scoring/` ·
  `event/` · `invariant/` · `Dealer` · `SkullKingEngine`.
- **인원 2~8 가변**, 10라운드 고정, 라운드 N = N장 (8인 9·10 라운드만 8장 — 덱 70장 한계).
- **비추이적 3자 순환**(해적>인어>스컬킹>해적 + "셋이 다 나오면 인어")을 6단 우선순위
  사다리 테이블로 환원 — `TrickResolver.LADDER`. §7 표 8조합 전수 테스트로 고정.
- **탈주(D-104)**: 개인전이라 티츄의 "상대팀 승리"(D-75)를 쓰지 않고 **남은 사람끼리
  계속** — 탈주 좌석은 유령으로 남아 자동조종(최약수, `timeoutAction` 과 정책 공유)이
  대신 둔다. 잔존 좌석 <2 또는 잔존 사람 0 이면 조기 종료, 탈주 좌석은 승자 후보 제외.
  순수 엔진 `desert`/`applyAndDrain`/`startRoundAndDrain` 구현 완료.
- **검증**: 305건, Docker 불필요. 2~8인 전 좌석 수가 무작위 합법수만으로 10라운드를
  완주하며(탈주 포함 시나리오 별도) 매 액션 직후 `SkullKingInvariantChecker` 를 통과한다.
- **통합(S5, D-102) 완료**: `SkullKingGameDefinition`(SKULL_KING, 2~8, AVAILABLE) 등록 →
  카탈로그·방 생성·디스패치·봇·타임아웃·resync 자동 연결. 어댑터 `SkullKingGameEngine` 이
  상태 I/O(티츄와 같은 Redis 키)·뷰(입찰 은닉 §5)·advance(라운드 연쇄)·desert(3치 포트,
  드레인 계약 내장)를 붙인다. 봇 풀 4→8(V10). 완료 기준: **4인·6인 봇 풀매치 10라운드
  완주 IT** (`SkullKingBotMatchSimulationIT`).
- **클라(S6, D-103) 완료**: `useStompRoom` 을 `RoomEventSink` 주입으로 게임 중립화하고
  (`tichuStore` 0줄 수정) `features/skullking/` 에 게임판을 새로 만들었다. 2~8 가변 좌석은
  **Row-Flow**(auto-fit 좌석 그리드 + 재생순 트릭 레일)로 폭 미디어 쿼리 0개. CSS 는
  `styles/parts/18-skullking-table.css` 에 `.sk-` 접두로 격리하고 네임스페이스 규칙을
  테스트로 기계화했다. 375×667 8인 실측 완료.
- **남은 것**: 매치 결과 영속·ELO·desert_count 는 D-02 스키마 결정(게임별 rating 분리) 선행으로
  별건(D-102 보류). 끊김 유예 구간 정지(D-104 한계)도 미해결.

---

> 최신 결정/번복은 `docs/decisions.md`, 진행 단계는 `docs/plans/mvp-roadmap.md` 가 정본.
> 본 문서와 어긋날 경우 그쪽을 신뢰한다.

---

*문서 생성: 코드베이스 정적 분석 기반(서버 프로덕션 210 파일 / 테스트 81 파일, 마이그레이션 V1~V10 확인).*
*최종 대조: 2026-08-01 (D-105, §12 배포·데모 계정 + 실측 수치 갱신). 본 문서는 정적 스냅샷이므로 `docs/plans/mvp-roadmap.md`·`docs/decisions.md`가 우선한다.*
