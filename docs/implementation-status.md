# Mirboard 구현 현황

> 지금까지 **실제로 구현된 기능**을 end-to-end로 정리한 현황 문서.
> 구조/흐름은 `docs/architecture.md`, 의사결정 이력은 `docs/decisions.md`(D-01~D-134),
> 단계별 진행은 `docs/plans/mvp-roadmap.md` 참조.
> 기능 설명의 세부 계약은 `docs/api.md`(REST), `docs/stomp-protocol.md`(STOMP),
> `docs/game-port.md`(`GameEngine` 포트), `docs/rules-tichu.md`·`docs/rules-skullking.md`·
> `docs/rules-onecard.md`(룰)가 정본이다.

---

## 0. 한눈에 보기

플랫폼은 **동작하는 MVP** 상태다. 로비/방 → **게임 6종**(티츄 4인 2:2 팀전 / 스컬킹
2~8인 개인전 / 원카드 2~6인 개인전 + 실시간 경쟁 / 요트 1~4인 주사위 / 오목 2인 1:1·4인 2:2 / 구름항로 2~4인 주사위·부동산) 풀게임 → 점수·ELO 영속(게임별, D-115) → 봇과 연습(D-136) →
재접속/탈주 처리 → UI(라이트/다크) 까지 end-to-end로 연결되어 있다.

| # | 기능 | 상태 | 핵심 위치 |
|---|------|------|-----------|
| 1 | 회원가입 / 로그인 / 프로필 (JWT) | ✅ | `domain.lobby.auth`, `infra.rest.auth`, `infra.rest.me` |
| 2 | 게임 카탈로그 (확장형) | ✅ | `domain.game.core.GameRegistry`, `infra.rest.games` |
| 3 | 방 생성/입장/준비/퇴장/관전 | ✅ | `domain.lobby.room`, `infra.rest.rooms`, `lua/room_*.lua` |
| 4 | WebSocket/STOMP 실시간 | ✅ | `infra.ws`, `infra.config.WebSocketConfig` |
| 5 | 티츄 룰 엔진 (전 페이즈 + 특수 카드) | ✅ | `domain.game.tichu` |
| 5b | 스컬킹 (룰 엔진 + 배선 + 클라 게임판) | ✅ | `domain.game.skullking`, `features/skullking` (§16) |
| 5c | 원카드 (룰 엔진 + 서버 배선·봇·기록 + 클라 게임판·경쟁 버튼·튜토리얼·턴 카운트다운 — D-131 공개, `MIRBOARD_ONECARD_STATUS` 로 되돌림) | ✅ | `domain.game.onecard`, `infra.bot.EngineTimerScheduler`, `client/src/features/onecard`, `docs/rules-onecard.md` (D-127~D-131, §17) |
| 5d | 요트 (동시 진행 D-202 · 개인 최고 점수 D-201 · 순수 엔진 + 포트 확장 `GameContext.turnSeconds`·차례 전체 타이머·봇·기록 + 클라 주사위 판·점수표·튜토리얼 — D-200 공개, `MIRBOARD_YACHT_STATUS` 로 되돌림) | ✅ | `domain.game.yacht`, `client/src/features/yacht`, `docs/rules-yacht.md` (D-181~D-183·D-200, §18) |
| 5e | 오목 (15×15 — 1:1·2:2 팀전, 렌주·삼삼만 금지·자유 오목 금수, 봇 3단계, 휴대폰 두 단계 확정 착수, 기록·무승부 — 게임 중립 확장 3건 D-176, D-190 공개, `MIRBOARD_OMOK_STATUS` 로 되돌림) | ✅ | `domain.game.omok`, `client/src/features/omok`, `docs/rules-omok.md` (D-175~D-180·D-190, §19) |
| 6 | 봇 플레이어 (빈 좌석 자동 채움) | ✅ | `infra.bot`, `domain.game.tichu.bot` |
| 7 | 재접속 동기화 (resync) | ✅ | `RoomService`, `GET /rooms/{id}/resync` |
| 8 | 탈주/끊김 처리 (유예→패널티) | ✅ | `infra.ws` 탈주 핸들러, `DesertionService` |
| 9 | 랭킹 / ELO | ✅ | `domain.game.scoring.EloCalculator`, `infra.rest.users` |
| 10 | 채팅 (로비 / 방) | ✅ | `infra.ws.lobby`, `infra.ws.RoomChatController` |
| 11 | UI 리디자인 (Tailwind+shadcn, 다크) | ✅ | `client` (Phase 20, D-76/77) |
| 12 | 멀티 인스턴스 (opt-in) | ✅(opt-in) | `infra.messaging.RedisMessageGateway` |
| 13 | 관측성 (Prometheus 게임별 차원, Grafana, Sentry) | ✅ | `infra.metrics`, `infra.config.SentryConfig`, `ops/grafana` (D-107) |
| 14 | **`GameEngine` 포트 (멀티게임 기반)** | ✅ | `domain.game.core.GameEngine`, `infra.ws.GameEngineProvider`, `TichuGameEngine` |
| 5f | 구름항로 (독창 주사위·부동산 2~4인 — 순수 엔진·포트 어댑터·봇·기록 + 클라 7×7 판·결정 패널·튜토리얼, 포트 확장 0건 — D-214 공개, `MIRBOARD_CLOUDROUTE_STATUS` 로 되돌림) | ✅ | `domain.game.cloudroute`, `client/src/features/cloudroute`, `docs/rules-cloudroute.md` (D-210~D-214, §17~§19) |
| 5g | 씨앗포 (턴제 포격 — 서버 정수 물리·연출 대기 엔진 타이머·봇·기록 + 클라 Canvas 전장·대본 재생·터치 조작·튜토리얼 — D-223 공개, `MIRBOARD_ARTILLERY_STATUS` 로 되돌림) | ✅ | `domain.game.artillery`, `client/src/features/artillery`, `docs/rules-artillery.md` (D-220~D-227, §20 — S4 포대·아이템, S5 2:2 팀전·딜레이 차례, 그림 스킨 층 `docs/artillery-skins.md`) |
| 15 | 테이블 좌석 배치 (원카드·스컬킹 시계 방향 테이블형 좌석 + "다음" 표시·방향 화살표, 티츄 +1 을 왼쪽에 — 클라만) | ✅ | `client/src/components/seatOrder.ts`(`tableLayout`), `features/tichu/seatView.ts`, `docs/plans/table-seating.md` (D-132) |
| 16 | 방 초대 링크 (공유창·복사·수동, 방마다 다른 카톡 미리보기, 로그인·게스트·가입 뒤 그 방으로, 자리 없음 → 바로 관전 + 안내, 대기실 끊김 120초 유예·화면 재동기화) | ✅ | `features/lobby/InviteLinkButton.tsx`·`shareRoomLink.ts`·`SpectateNotice.tsx`, `features/auth/returnPath.ts`, `infra.web.RoomPagePreviewController`, `infra.ws.WaitingLeaveGraceScheduler`, `ws/useRoomMeta.ts`, `docs/plans/room-invite.md` (D-133·D-134) |

설계 단계(Phase 1)부터 클라이언트 통합·UI 리디자인(Phase 20)까지 로드맵 항목이 완료
표시되어 있다(`docs/plans/mvp-roadmap.md`).

---

## 1. 인증 (Phase 2b)

- **회원가입** `POST /api/auth/register` — username(별명 — 한글·영문·숫자 2~10자, D-149) +
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
- 응답에 `supportedRoomOptions`(D-106) — 게임이 실제로 쓰는 방 설정
  (`TARGET_SCORE`·`TEAMS`·`BETTING`). 티츄만 셋 다, 스컬킹은 없음. `TARGET_SCORE`·`BETTING`
  은 노출/검증을 가르고, `TEAMS` 는 좌석 정책 **라벨**만 가른다(D-106 정정).
- D-176 `roomOptionChoices`(값을 가진 방 옵션의 선택지) · D-196 `playerCounts`(할 수 있는 인원 — 맵 옵션 조합의 시작 인원~정원 합집합, 오목 [2, 4]).
- **확장성**: 새 게임은 `domain.game.{newgame}` + `GameDefinition @Component` 등록만으로
  카탈로그·방 생성·엔진 디스패치에 자동 연결(REST/로비 코드 무변경).
  D-98 이후 이 문장은 **인게임까지 참**이다 — §14 참조.

관련 테스트: `GameRegistryTest`, `GameDefinitionPlayerCountsTest`, `GameCatalogIntegrationTest`.

---

## 3. 방 관리 (Phase 2d, 16, 18)

- **생성** `POST /api/rooms` {name, gameType} — 생성자가 호스트. 정원은 선택 필드
  `capacity`(D-99), 생략하면 `GameDefinition.maxPlayers()`. 게임이 정한
  `minPlayers()..maxPlayers()` 밖이면 `INVALID_CAPACITY` — 티츄는 4~4 라 무변경.
  Redis `room:{id}` + `room:{id}:players` 저장. D-136 — `fillWithBots=true` 는 **봇과 연습**(방장 + 봇, 만들자마자 IN_GAME,
  목록에 안 오름), 방 만들기 모달은 "친구와 하기 / 봇과 연습"(기본 친구와 하기 — 게스트 포함, 봇 체크박스 없음).
- **지금 시작** `POST /api/rooms/{id}/start`(D-136, 방장) — `room_start_now.lua` 가 최소 인원 이상·방장 말고 모두 준비·정원 미만이면
  정원을 현재 인원으로 줄이고 WAITING→IN_GAME. 대기실의 "지금 N명으로 시작"(티츄 없음).
- **자리에 앉기**(D-137) — 대기실 관전자는 빈자리가 있으면 [자리에 앉기](`/join`). `room_join.lua` 가 관전자 목록에서도 빼 이중 소속이
  없다. 대기실 재동기화는 관전자를 관전으로 돌려놓는다.
- **목록** `GET /api/rooms?gameType=&status=` — status로 필터.
- **입장** `POST /api/rooms/{id}/join` — `room_join.lua` 원자 검증(정원/멤버십).
  **capacity 자동시작 폐기**(Phase 16): 입장만으로는 시작하지 않는다.
- **준비** `POST /api/rooms/{id}/ready` — `room_ready.lua` 로 ready SET 토글. **정원 4 +
  전원 ready** 시에만 WAITING→IN_GAME 원자 전이(봇은 join 시 서버가 자동 ready).
- **퇴장** `POST /api/rooms/{id}/leave` — WAITING은 즉시 퇴장, IN_GAME은 탈주 처리(§8).
  빈 방은 `room_leave.lua`/`room_delete.lua` 가 관련 키까지 정리.
- **재입장(멱등)** `POST /api/rooms/{id}/join-or-reconnect` — JOINED/RECONNECTED/SPECTATING
  모드 반환. 나갔다 재입장 시 ALREADY_IN_ROOM 버그 제거(Phase 18, D-74). 읽은 뒤 자리가 찬 경합은 `ROOM_FULL`·`GAME_ALREADY_STARTED`
  그대로(관전으로 바꾸지 않는다 — 방 화면 패널), 같은 사람의 동시 이중 요청은 늦은 쪽도 RECONNECTED(D-133).
- **좌석 정책** `PUT /api/rooms/{id}/team-policy` (SEQUENTIAL/RANDOM/MANUAL, 호스트만).
  이름과 달리 실체는 좌석 순서 정책이라 **게임을 가리지 않는다** — `RANDOM` 은 시작 직전
  좌석을 섞고, 개인전에서도 좌석 순서 = 턴 순서다(D-106 정정).
- **게임별 옵션 게이팅**(D-106): `targetScore`·`stake` 는 게임이 `supportedRoomOptions`
  로 선언한 것만 허용. 미지원인데 기본값 아닌 값이 오면 거절 — 예전엔 스컬킹 방에 stake 를
  걸면 통과한 뒤 `RoomChipService` 가 조용히 return 해서 칩 없이 봇만 금지됐다.
- **중단** `POST /api/rooms/{id}/abort` (호스트, in-game → FINISHED). D-135 — 방장 말고 모두 봇인 판만(`HUMAN_PLAYERS_PRESENT` 409), 버튼은 세 게임판 머리 줄의 "게임 종료"(같은 조건에서만 — 예전 대기실 셸 버튼은 게임 중에 그려지지 않아 보인 적이 없었다).
- **관전** `POST`/`DELETE /api/rooms/{id}/spectate` — 관전자는 손패·액션 불가(§상태 은닉).
- **재동기화** `GET /api/rooms/{id}/resync` — tableView + privateHand + seq(§7).
- **초대 링크(D-133)** — 방 주소 그대로(`/rooms/{id}`)를 대기실 "초대 링크"가 공유창(Web Share API) → 복사 → 링크가 선택된 입력란 순으로
  건넨다. 서버 `GET /rooms/{id}`(공개 HTML, `/api` 밖)가 빌드된 `index.html` 의 표식 구간을 그 방의 Open Graph(게임·방 이름·방장·정원 —
  현재 인원 제외)로 바꿔 카톡 미리보기가 방마다 다르다(요청의 Accept 와 무관). 방이 없거나 틀이 없으면 기존 SPA 그대로(`docs/api.md`).
  공유창은 휴대폰·태블릿(굵은 포인터)만 — PC 는 복사. 꽉 찬 대기실(봇 자리 포함 — 봇은 자리를 비켜 주지 않는다)의 초대 설명은 "링크를 받은
  친구는 관전으로 들어와요". 방장이 공유창에서 카톡으로 나가 기다려도(휴대폰 소켓이 죽어도) 대기실 끊김 120초 유예로 방과 자리가 남는다(D-134).

동시성 보장: capacity 위반 0건(`RoomServiceConcurrencyIT`, 멀티스레드 join).
`join-or-reconnect` 의 입장 갈래(봇까지 꽉 찬 방 관전·경합 그대로·동시 이중 요청 재접속)는 `RoomServiceJoinOrReconnectTest`(Docker 불필요, D-133).
관련 테스트: `RoomControllerIntegrationTest`, `RoomResyncIntegrationTest`,
`RoomCapacityIntegrationTest`(D-99 인원 가변 — 가변 게임은 테스트용 fake `GameDefinition`).

---

## 4. WebSocket / STOMP (Phase 2e~)

- 엔드포인트 `ws://host/ws` (STOMP + SockJS), CONNECT JWT 인증.
- **공개 토픽**: `/topic/lobby/rooms`, `/topic/room/{id}`, `/topic/room/{id}/chat`.
- **비공개 큐**: `/user/queue/room/{id}` (HAND_DEALT, CARDS_RECEIVED, 원카드 HAND_UPDATED, ERROR). 브로커 prefix 는 `/queue`
  (D-126 — 예전 `/user/queue` 설정에서는 Spring 이 바꾼 본인 세션 목적지가 버려져 한 번도 배달되지 않았다).
  `/queue/...` 직접 구독은 `StompAuthChannelInterceptor` 가 거절.
- **클라→서버**: `/app/room/{id}/action`, `/app/room/{id}/chat`.
- 모든 메시지는 `{ eventId, seq, type, ts, payload }` envelope. 서버는 클라가 보낸 `seq`를
  무시하고 `room:{id}:seq` INCR 값을 권위 카운터로 사용.
- **비공개 이벤트는 순번을 쓰지 않는다(D-126 티츄, D-129 원카드)**: `GameEvent.sequenced()` 포트 확장. 예전엔
  카드를 낼 때마다 비공개 `HAND_DEALT` 가 순번을 써서 다음 공개 이벤트가 항상 구멍 → 낼 때마다
  4명 전원 resync(봇 매치 모델 실측 플레이당 1.263회/클라). 지금은 공개 순번이 구멍 없이 이어지고,
  소원 해제는 공개 `WISH_CLEARED` 로 알리며, 클라 리듀서가 라운드 종료 뷰·종료 표시·에러 문구를 직접
  맞춘다 — 플레이당 0.036회, 남은 것은 라운드 경계(`ROUND_STARTED`)뿐(`TichuEventStreamIT` 가 단언).
  원카드 손패 이벤트(`HAND_DEALT`·`HAND_UPDATED`)도 같은 확장을 쓴다(D-129 — 손패 순서는 `handVersion` 이 지킨다).
  스컬킹 `HAND_DEALT` 는 아직 순번을 쓴다(라운드당 1회 구멍, D-103 수용).

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
`DeclareGrandTichu`, `DeclareTichu`, `Ready`, `PassCards`, `PlayCard`(cards + 선택 `wishRank`),
`PassTrick`, `GiveDragonTrick`. 페이즈별 검증은 `ActionValidator`,
소원 충족 판정은 `WishFulfillmentChecker`.

### 5.3 카드 / 족보 / 특수 카드
- 카드 모델 `Card`(일반 suit+rank / 특수 MAHJONG·DOG·PHOENIX·DRAGON), `Deck` 셔플.
- 족보 판별 `HandDetector`, 비교 `HandComparator`(sealed `Hand`). 폭탄(BOMB)은 트릭 인터럽트.
- 특수 카드: **마작**(소원 호출), **피닉스**(와일드, 트릭 획득 시 −25), **드래곤**(최강 단일,
  +25, 트릭을 상대팀에 넘김), **개**(다음 차례 강제).
- 마작 소원은 `PLAY_CARD` 에 `wishRank` 를 동봉해 한 프레임으로 처리(D-109) — 클라는
  마작이 포함된 선택을 낼 때 `MakeWishModal` 을 먼저 거친다. 드래곤 트릭 → GIVE_DRAGON_TRICK
  흐름은 기존대로 `GiveDragonTrickModal` 과 연동.

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
- D-136 — 봇은 **봇과 연습** 방에만 들어간다(방 만들기에서 고름 — 방장 + 봇으로 만들자마자 시작, 봇은 join 시 서버가 자동 ready). 친구와 하기 방에는 봇이 없다(사람이 모자라면 방장이 "지금 N명으로 시작").
- `infra.bot.BotScheduler` 가 봇 차례에 포트 `botAction` 을 부르고, 각 게임 어댑터가 자기 정책에
  위임한다. 딜레이 `mirboard.bot.delay-millis`(기본 700ms, D-76 후속 상향).
- **티츄 봇 = `HeuristicBotPolicy`(D-118)** — 공개 정보(`BotView`)만 보는 결정적 휴리스틱. 선언·패스·
  리드·팔로우·폭탄·소원·개·용 양도·봉황을 다루고 파트너 선언 가드를 둔다. 후보는
  `LegalActionEnumerator.enumerateFull`(기존 후보 + `ComboFinder` 조합 + 선언, `ActionValidator`
  필터), 제안이 검증에 실패하면 `TimeoutActionPolicy` 로 폴백. 순수 시뮬레이션: 랜덤 상대 100/100,
  그리디 기준선 듀플리케이트 200딜 Δ>0 143/186, 선언 성공률(보정 밖 시드) 티츄 0.729·그랜드 0.778.
  `RandomBotPolicy` 는 평가 기준선으로만 남았다.
- **스컬킹 봇 = `SkullKingBotPolicy`(D-230, D-119 대체)** — 공개 정보 뷰(`SkullKingBotView` — 플레이 중 공개 예측·
  지난 라운드 예측·이번 라운드 트릭 포함) 위의 결정화 몬테카를로. 빈 수트에 맞게 남의 손패를 뽑고(플레이 중엔 공개
  예측에 맞는 패에 무게), 후보마다 상대 모델(필요에 따라 이기고 지는 굴리기)로 라운드 끝까지 굴려 내 점수 평균이 가장 높은 수.
  예산 시뮬레이션 카드 30,000(시간 아님, 시드는 뷰에서) — 한 수 p99 3.4ms. 2~8인 대 기존 봇 +126~+213점, 8인 대 최약수
  +248(기존 −3), 자가대전 적중률 약 0.68(`rules-skullking.md` §16). D-119 휴리스틱은 `OddsHeuristic` 으로 비교 기준만.
- 두 정책 모두 예외·비합법 수를 내면 ERROR 로그 후 `timeoutAction`(최약수)으로 폴백한다.
  `timeoutAction`·탈주 유령 자동조종은 최약수 그대로(D-104).
- 봇 라벨은 UI에서 제거(D-76 후속), 봇 매치는 ELO 집계 제외(D-71).
- `TurnTimeoutScheduler` 가 무응답 차례를 자동 처리.

---

## 7. 재접속 동기화 (Phase 8A)

- 새로고침/탭 복귀 시 WS 자동 재연결(동일 JWT) → `GET /api/rooms/{id}/resync` 로
  tableView + privateHand + seq 수신 → 토픽/큐 재구독.
- `seq` 갭으로 누락 판단, 필요 시 전체 resync(RESYNC 이벤트)로 복구.
- resync 는 방 액션 락 안에서 상태·`eventSeq`·뷰를 함께 읽는다(D-126) — 액션 사이에 끼어
  스냅샷과 순번이 어긋나던(이벤트 이중 적용·누락) 경합 제거. 락을 약 3초 못 잡으면 잠금 없이 읽는다.
- 손패는 항상 `/user/queue` 로만 복원(공개 토픽 누출 금지).
- **남은 턴 시간(D-131)**: 응답의 `turnRemainingMs` = 지금 세대 턴 데드라인 − 지금(턴 제한 끔·기다리는 좌석 없음·데드라인
  없음이면 null). 같은 락 안에서 읽고, 진행 경로는 다음 턴 데드라인을 락을 풀기 전에 건다 — 새 상태와 이전 턴의 남은 시간이
  섞이지 않는다. 원카드 게임판 카운트다운이 쓴다(재접속 직후에도 맞다).

관련 테스트: `RoomResyncIntegrationTest`, `RoomJoinOrReconnectIntegrationTest`,
`RoomControllerResyncLockTest`(D-126·D-131), `TurnRemainingTest`(D-131).

---

## 8. 탈주 / 끊김 처리 (Phase 19 D-75, 확장 D-96·D-104)

- 서버가 STOMP Subscribe/Disconnect 를 후킹한다. 프레즌스는 **Redis `RoomPresence`**
  (세션 카운터 HASH) — D-75 의 in-memory `WsSessionRegistry` 는 D-96 에서 대체됐다.
  in-memory 로는 인스턴스 A 에 붙은 재접속을 B 가 못 봐서 "재접속했는데 탈주 처리"가 난다.
- **WAITING 끊김** = 플레이어는 준비 해제 + 유예(`mirboard.waiting.grace-seconds`, 기본 120s — D-134) 뒤 미복귀면 leave
  (마지막이면 방 소멸), 같은 방 라이브 세션이 남아 있으면(두 탭 중 하나 닫기) 아무것도 안 함. 관전자는 즉시 관전 종료(관전자 0 방 소멸).
  대기실 화면은 다시 연결·다시 보임·online 때 `join-or-reconnect` 로 방을 다시 맞춘다(방 없음 → "방이 없어졌어요"). D-75 는 즉시 leave 였다.
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
- **좌석 고정·종료 방 정지(D-122)**: 시작된 게임의 좌석 목록은 매치가 끝나기 전엔 바뀌지 않는다 —
  진행 중 매치의 탈주 미처리 '나가기'는 no-op, FINISHED 방 나가기는 좌석 불변(600s TTL 로 소멸).
  resync 는 요청자가 실제 앉은 좌석의 비공개 뷰만 준다. 액션·봇·턴 타임아웃은 IN_GAME 이 아니면
  멈추고, 탈주 MATCH_ENDED·강제 종료는 방 액션 락 안에서 FINISHED 전이 + 턴 데드라인 취소
  (`GameAbortService`, 호스트 요청은 락 전에 사전 검증). 리매치는 게임이 선언(`supportsRematch()` —
  티츄만)하므로 스컬킹 사람끼리 매치도 정상 종료 시 FINISHED. 남은 것: 티츄 리매치 대기 방의
  leave 좌석 당김, 대기실 나가기와 게임 시작의 ms 단위 경합(`room_leave.lua` 원자화 후속).
- 프레즌스는 **세션당 1회** 집계한다(D-111). 클라가 한 세션에서 방 토픽을 3개 구독하는데
  구독마다 세던 시절엔 끊긴 뒤에도 잔여 카운터가 남아 탈주가 확정되지 않았다.

관련 클래스/테스트: `DesertionService`, `RoomPresence`, `SkullKingDesertionTest`,
끊김/탈주 유닛 + `MatchResultRecorder` 탈주 IT.

---

## 9. 랭킹 / ELO (Phase 8D)

- **게임별**(D-115): 전적·레이팅은 `user_game_stats`(V11, `user_id+game_type`)에 쌓인다.
  티츄 기존 값은 V11 이 TICHU 행으로 이관했고 `users` 의 옛 컬럼은 쓰기를 멈췄다(DROP 예정).
- ELO: 티츄는 팀 평균(`applyMatch`), 스컬킹은 개인전 쌍대(`applyFreeForAll` — 탈주 좌석
  최하위). K=40(30판 미만)/32. 봇이 낀 매치는 승패만 기록, ELO 제외(D-71).
- 스컬킹 매치는 `skullking_match_results/participants` 에 남는다(`SkullKingMatchRecorder`).
- **tier** 는 컬럼이 아니라 rating 구간에서 계산(BRONZE→…→MASTER, derived).
- 엔드포인트: `GET /api/users/{id}/stats`(`games[]`), `GET /api/users/ranking?gameType=`
  (게임별, 기본 TICHU), `GET /api/me`(승패 = 전 게임 합). 클라는 랭킹 게임 탭·프로필
  게임별 전적·헤더 대표 티어(가장 많이 한 게임).

관련 테스트: `EloCalculatorTest`, `UserGameStatsServiceIT`, `V11UserGameStatsMigrationIT`,
`SkullKingMatchRecorderIT`, `UserStatsIntegrationTest`, 클라 `RankingCard`·`GameRecords`.

---

## 10. 채팅 (Phase 8B)

- ~~로비(전역) 채팅~~ — D-146 에서 화면·서버 모두 제거.
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
- **게스트 체험(D-117)**: 로그인 화면 「게스트로 바로 체험하기」 → `POST /api/auth/guest`. 헤더 '게스트'
  배지, 아바타·비번 변경 비활성, 로그아웃 시 "다시 들어올 수 없어요" 확인.
- **로그인 뒤 그 방으로(D-133)**: 보호 경로가 `/login` 으로 보낼 때 가려던 경로를 `state.from` 에 싣고, 로그인·게스트 입장·가입 뒤(그리고
  로그인 상태로 `/login` 에 오면) `safeReturnPath(from) ?? '/games'` 로 `replace` — 사이트 안 경로만(오픈 리다이렉트 금지). 방 경로에서 왔으면
  "방에 초대받았어요" 안내와 "게스트로 바로 입장"을 로그인 폼 앞에. 방 화면은 서버가 관전으로 받았으면(`mode=SPECTATING` + 진행 중·꽉 찬 대기실)
  맨 위에 "…관전으로 들어왔어요" 안내(`SpectateNotice` — 세 렌더 분기 모두, 탭에서 한 번: 새로고침·허브 "구경하기"·패널 관전에는 안 뜸), 첫 입장이
  `ROOM_FULL`·`GAME_ALREADY_STARTED`(확인과 입장 사이 경합)면 "자리가 없어요 — 관전할까요?", `ROOM_NOT_FOUND` 면 "방이 없어졌어요" 패널. 방 화면을
  떠나면 탭 제목을 "Mirboard" 로 되돌린다.
- **튜토리얼(D-121)**: 게임별 레지스트리(`features/tutorial/gameTutorials.ts`) + 게임 중립
  `TutorialDialog`/`useTutorialGate`. 진입점은 허브 게임 카드 '게임 방법', 대기실 헤더 버튼 + 그 게임
  대기실 첫 입장 1회 자동 노출(D-154 에서 폐지 — 버튼으로만), 스컬킹 게임판 '규칙'(수동). 스컬킹은 13단계(단계마다
  `rules-skullking.md` § 인용) + '누가 이길까?' 4문제. A2 의 허브 첫 방문 자동 노출은 여기로 옮겼다.
- **Phase 20(D-76/77)**: Tailwind v3(`preflight:false`) + shadcn/ui(slate, CSS vars),
  라이트/다크 토글(`themeStore`, `<html>.dark`, 기본 dark → D-146 기본 light). 게임판 기하는 `styles.css` 유지,
  좌석 컴팩트화·경기장 확대 등 후속 개선.
- **게임판 ⋮ 메뉴·경기장 고정·확대(D-186)** ✅: 일곱 게임판 머리 줄은 한 줄 고정(게임 이름·핵심 배지·채팅 + ⋮). 방 정보·효과음·라이트/다크·
  게임 설정(티츄 카드 애니)·규칙·점수표·이모지·기권·게임 종료·나가기는 게임 중립 `components/GameMenu`(body 포털, `role=menu`, 바깥 누르기·Esc,
  화살표·Home·End) — 공용 CSS `24-game-menu.css`(`.gm-`), 테스트 도우미 `src/test/gameMenu.ts`. 티츄도 나가기(진행 중이면 팀 패배 확인)·규칙.
  휴대폰(≤480px)은 좌우 여백을 줄이고 경기장 최소 높이를 `100dvh` 기준으로 키웠다(17 의 D-186 블록 — 스컬킹 천은 입찰↔플레이 높이가 뛰지 않게,
  원카드 "나" 줄·요트 상태 줄은 한 줄). 실측(라이트·다크 같은 값, 머리 줄 → 경기장 높이 px, 가로 넘침 전후 0):

  | 게임 | 375×812 전 | 375×812 후 | 390×844 전 | 390×844 후 |
  |---|---|---|---|---|
  | 티츄 `.table-arena` | 머리 124 · 394 | 머리 44 · **474** | 124 · 394 | 44 · **506** |
  | 스컬킹 `.sk-felt` | 151 · 212(입찰)/344(플레이) | 44 · **440**(둘 다) | 124 · 212 | 44 · **472** |
  | 원카드 `.oc-felt`(4인) | 124 · 412 | 44 · **492** | 124 · 412 | 44 · **524** |
  | 요트 `.yt-felt` | 116 · 207 | 44 · **279** | 116 · 209 | 44 · **311** |
  | 구름항로 `.cr-board`(정사각) | 81 · 331 | 44 · **355** | 81 · 346 | 44 · **370** |

관련 테스트(Vitest): `tichuStore.*`, `authStore`, `roomChatStore`, `effectStore`, `handType` 등.

---

## 12. 운영 / 인프라 기능

- **멀티 인스턴스(opt-in)**: `mirboard.messaging.gateway=redis` 시 Redis Pub/Sub fan-out
  (`RedisMessageGateway`) — STOMP 프레임만 인스턴스 간 전파하고, 도메인 이벤트는 발행한 인스턴스에서만
  처리한다(D-116 — 리스너 부수효과가 공유 저장소라 재처리는 중복 기록이 된다). 기본은 in-memory 단일 인스턴스.
- **관측성**(D-107, C3): Prometheus(`/actuator/prometheus`, `MirboardMetrics`), MDC 로그
  (`userId`/`roomId`/`eventId`, `MdcKeys`). 게임 활동 카운터 4종은 `gameType` 차원 —
  `mirboard_room_opened_total`·`game_started`·`round_completed`·`match_completed`
  (이름에 `.created` 를 쓰면 OpenMetrics 예약어와 충돌해 접미사가 잘린다).
  로비/프로토콜 카운터(`room_joined`·`action_rejected`)는 평면.
- **로컬 관측 스택**(D-107): `docker compose --profile observability up -d` →
  Prometheus(:9090) + Grafana(:3000, 익명 열람). 데이터소스·대시보드는 `ops/grafana`
  에 프로비저닝돼 컨테이너를 지워도 화면이 남는다. SaaS 계정 불필요.
- **Sentry**(D-107): `sentry-logback` 어펜더로 ERROR 로그 캡처. `SENTRY_DSN` 미설정 시
  **Bean 자체가 생성되지 않는다**. 공식 Spring Boot 스타터는 Boot 4 비호환(스타터 자신이
  경고) — 상세는 `SentryConfig` javadoc. PII 전송 끔.
- **배포**: `client` 빌드를 서버 정적 리소스로 번들해 단일 jar 서빙, `Dockerfile`/`fly.toml`
  멀티스테이지로 Fly.io. CD 는 `.github/workflows/deploy.yml`(main 푸시 + 수동 트리거) —
  `FLY_API_TOKEN` 미설정이면 잡이 스스로 건너뛴다. **라이브: https://mirboard.fly.dev**
  (2026-10-03, 도쿄 리전. 유휴 시 머신이 멈춰 첫 접속에 약 30초 콜드 스타트). Redis 는 Fly 자체
  앱 `mirboard-redis`(D-114), DB 는 Fly Postgres `mirboard-db`.
- **게스트 체험(D-117, D-105 데모 계정 대체)**: `POST /api/auth/guest` 가 방문자마다 일회용 `users`
  행(규약 `guest-`+sentinel 해시, D-02 불변)을 만들고 JWT 를 준다. 비번/아바타 403, 랭킹 제외,
  게스트 포함 매치 ELO 미적용(`RatedMatchPolicy`). IP(IPv6 /64)당 하루 10회 + UTC 하루 전역 200명
  (Lua 원자 카운터, fail-closed). 48h 미참여 게스트 자동 정리(10분 1회). 킬스위치
  `MIRBOARD_GUEST_ENABLED`. 공유 데모 계정은 같은 계정 동시 접속이 좌석 탈취·손패 유출을 일으켜 폐기.
- **허브 서비스 토큰(D-170)**: 허브 서버가 `/api/admin/**` 를 서버끼리 부르는 인증. `JwtAuthFilter` 안의
  분기(`AdminServiceProperties`, `MIRBOARD_ADMIN_SERVICE_TOKEN`·`MIRBOARD_ADMIN_SERVICE_USER_ID`) —
  `/api/admin/` 경로에서만, 상수 시간 비교, 토큰 비었거나 id ≤ 0 이면 꺼짐(기본). 서비스 사용자는 운영자 본인
  userId 이고 권한은 그 `admin_roles` 등록으로 기존 게이트를 그대로 쓴다. 테스트: `JwtAuthFilterServiceTokenTest`(단위 9, Docker
  불필요)·`AdminServiceTokenIT`(5).
- **레이트리밋 보정(D-117 ← D-90)**: `/api/auth/**` 는 Bearer 와 무관하게 항상 IP 키, 클라 IP 는
  신뢰 헤더(운영 `Fly-Client-IP`)·IPv6 /64, 버킷·대상 판정은 원본 URI 가 아니라 디코딩·contextPath
  제외 경로(인코딩·`X-Forwarded-Prefix` 우회 차단). 429 `Retry-After` = 버킷 윈도.

---

## 13. 테스트 현황

- **서버**: **1971건** (D-223 시점 — 씨앗포 공개 뒤, `--rerun` 실측 실패 0·건너뜀 5, 그중 Docker 불필요 1690건(86%) — 씨앗포 도메인 51건(Docker 불필요 49)·서버 경로 IT 3·기록 실패 격리 1. D-196 시점 1955건 — 여섯 게임 공개 뒤 + 카탈로그 `playerCounts` 단위 4건, `--rerun` 실측 실패 0·건너뜀 5, 그중 Docker 불필요 1679건(86%) — 오목 도메인 144건(Docker 불필요 138)·오목 서버 경로 IT 4·기록 실패 격리 1. D-200 시점 1825건 — 요트 도메인 106·Docker IT 5건과 오목·구름항로 도메인 포함, `--rerun` 실측 실패 0, 그중 Docker 불필요 1567건(86%). D-165 시점 1495건 — 계급 5·왕관 IT 1·찾기 난이도 1건, 그중 Docker 불필요 1253건. D-162 시점 1489건 — 내보내기 단위 6·IT 1건, 그중 Docker 불필요 1248건. D-161 시점 1482건 — 대기실 설정 단위 9·IT 2건, 그중 Docker 불필요 1242건. D-160 시점 1471건 — 게임별 판수 IT +1, 그중 Docker 불필요 1233건. D-158 시점 1470건 — 하트비트 IT +1, 그중 Docker 불필요 1233건. D-156 시점 1469건 — 비밀번호 길이 규칙 +9, 그중 Docker 불필요 1233건. D-155 시점 1460건 — 음성 신호 중계 +6, 그중 Docker 불필요 1224건. D-154 시점 1454건 — 상대 전적 IT +1, 그중 Docker 불필요 1218건. D-153 시점 1453건 — 대기실 복귀 IT +4·매치 진행 규칙 재작성, 리매치 테스트 삭제, 그중 Docker 불필요 1218건. D-149 시점 1452건 — 별명 규칙 +17건, 그중 Docker 불필요 1221건. D-143 시점 1435건 — 방장 시작에서 준비·정원 조건 테스트 3건 제거, 그중 Docker 불필요 1204건. D-140 시점 1438건 — 대기실 준비 경합 BY-3 IT 2건·진행 킥 턴 데드라인 3건·락 소유 확인 IT 4건, 그중 Docker 불필요 1206건. D-138 미리보기 이미지 시점 1429건 — 렌더러 이미지 2건. D-137 시점 1427건 — 관전자 착석 IT 1건. D-136 시점 1426건 — `RoomServiceStartNowTest` 6건 + `RoomModesIntegrationTest` 12건. D-135 시점 1408건 — `RoomServiceHostAbortTest` 6건 + 봇 판 강제 종료 IT 1건. D-134 시점 1401건. 전체 `--rerun` 실측 실패 0, 그중 Docker 불필요 1203건(84%) · Docker IT 226건. 대형 봇 평가 5건은 `MIRBOARD_BOT_EVAL=1` 전용이라 skip. D-131 공개 전환 시점 1327건 + 방 미리보기 `infra.web.*` 51건(그중 Docker 불필요 45건 — 그래핌 자르기·표식 경고 12건 포함) + 입장 갈래 `RoomServiceJoinOrReconnectTest` 5건 + 대기실 끊김 유예 18건(`WaitingLeaveGraceSchedulerTest` 14·`RoomDisconnectHandlerTest` +4, D-134 — 둘 다 Docker 불필요)). 게임별 내역(같은 실측 — D-133 은 게임 도메인 무변경): 티츄 도메인 312건(그중 Docker 불필요 304건), 스컬킹 도메인 375건(371건), 원카드 도메인 191건(187건) + 원카드 서버 경로 IT 10건(`OneCardRaceIT`·`OneCardBotMatchSimulationIT`)·엔진 타이머 단위 17건. 매치 기록 실패 격리는 세 게임 모두 `infra.ws.*RecorderFailureTest`(8건, D-130·D-131). 테스트 JVM 힙 1g · 컨텍스트 캐시 상한 4(IT 가 늘어 기본 512m 에서 OOM — D-122 검증 중 발견).
  단위(룰 엔진·족보·ELO·JWT·카탈로그·포트 어댑터) + 통합(Testcontainers PostgreSQL 16/
  Redis — auth/rooms/STOMP/봇/동시성/매치 영속/2-인스턴스 인계).
  집계 방식: `./gradlew :server:test --rerun` 뒤 `server/build/test-results/test/TEST-*.xml` 을 합한다. 클래스는 **파일 이름의 바깥 클래스**로
  묶는다 — `@Nested` 는 `$` 앞, `@DisplayName` 을 단 `@Nested` 는 스위트 이름이 표시 이름이라 이름 속성으로는 패키지에 안 묶인다(스컬킹이
  그렇다). 게임 도메인 = `com.mirboard.domain.game.{게임}.` 아래, Docker 불필요 = 바깥 클래스 이름이 `IT`·`IntegrationTest` 로 끝나지 않는 것.
  재현 스크립트는 `docs/case-study-multi-game.md` 부록 (6).
- 룰·봇 단위는 **Docker 불필요** — `./scripts/check.sh rules` 에 묶여 있다(티츄·스컬킹·원카드 룰 + 세 봇
  평가, ~20s). 스컬킹·원카드 매치 기록 IT(D-115·D-128)는 Docker 가 필요해 `rules` 에서 뺐다.
- **클라이언트**: **1273건 / 108파일** (D-226 시점 실측, 실패 0 — 씨앗포 42건·2:2 시작 인원 안내 1건 포함(그사이 main 에 들어온 다른 게임 테스트 포함). D-224 시점 1249건 / 107파일 — 씨앗포 36건 포함. D-223 시점 1235건 / 107파일 — 씨앗포 26건 포함. D-196 시점 1209건 / 103파일 — 오목 33건(게임판·스토어·판 입력·튜토리얼 28 + CSS 5)·인원 표기 +5건/+1파일 포함. D-200 시점 1114건 / 92파일 — 요트 게임판·스토어·튜토리얼·CSS 35건 포함. D-166 시점 1074건 / 87파일 — 색 계급 아이콘·좌석 계급. D-165 시점 1072건 / 86파일 — 좌석 채팅 한 줄·계급 아이콘·찾기 난이도. D-162 시점 1063건 / 85파일 — 내보내기 좌석 버튼·확인 창 +7건. D-161 시점 1056건 / 85파일 — 대기실 설정 패널·준비·버튼 크기, 방 만들기 옵션 테스트 정리. D-160 시점 1053건 / 84파일 — 차례 강조 +3·손패 2줄 +3·랭킹 순서 +2건. D-159 시점 1045건 / 84파일 — 음성 꺼짐 +1건/+1파일. D-158 시점 1044건 / 83파일 — 하트비트 설정 +1·음성 4명 +3건. D-157 시점 1040건 — 토스트 +5건/+1파일·마이크 오류 토스트 +1건. D-156 시점 1034건 / 82파일 — 티츄·스컬킹 음성 대화·비밀번호 4자 +3건. D-155 시점 1031건 — 음성 대화 훅·게임판 +11건/+1파일. D-154 시점 1020건 — 턴 선택지·가운데 칸·메인 배치·프로필·상대 전적·더블클릭·효과음/이모지·경쟁 버튼 +23건/+1파일. D-153 시점 997건 — 대기실 복귀 뒤 결과 화면 유지·[대기실로]·다음 판 관전 안내 +7건. D-152 시점 990건 — 더미 눌러 먹기·먹기 비행 +8건. D-150 시점 982건 — 별명 가입 칸 +1건. D-147 시점 981건 — 계급 배지 테마 색·대비 +4건/+1파일. D-146 시점 977건 / 79파일 — 색약 모드·로비 채팅 테스트 −6건/−1파일, 기본 라이트·로비 정리·참가자 카드 +5건/+1파일. D-145 시점 978건 — 사이트 이름 없는 메인 +1건. D-144 시점 977건 — 원카드 손패 뒷면·이전 카드·잡기 문구 +7건. D-143 시점 970건 — 대기실 방장 시작·방 만들기 인원 안내로 준비 관련 테스트 정리 −5건. D-141 시점 975건 — 게임판 지연 로딩 가드·조각 새로고침 +10건/+2파일. D-140 시점 965건 — 원카드 멈춘 차례 복구 +7건. D-139 시점 958건 — 원카드 상태 줄·카드 비행 +10건. D-138 시점 948건 / 77파일 — 미리보기 이미지 3건·오류 문구 한국어화 7건, +2파일. D-137 시점 938건 / 75파일 — 관전자 자리에 앉기 +5건. D-136 시점 933건 / 75파일 — 방 만들기 두 갈래·지금 시작 버튼·안내·연습 표시·허브 +23건. D-135 시점 910건 / 75파일 — 게임판 "게임 종료" 버튼 3게임·RoomPage 조건·재접속 배너 +18건/+1파일. D-132 후속 시점 892건 / 74파일 — 끝난 판 차례 숨김·스컬킹 리드 대기·라이트 대비·좁은 좌석 이름·481~560px·채팅 폴더 경계 +7건/+1파일. D-134 시점 885건 / 73파일. D-133 방 초대 링크 시점 865건 / 72파일(돌아갈 주소·공유 함수·로그인/가입·라우팅·방 화면·초대 버튼·관전 안내·허브 관전 기록·`index.html` 117건 포함) + 최종 리뷰 반영 20건(대기실 화면 재동기화 `useRoomMeta`·방 화면 14건, 관전 안내 상태 영역 5건, 수동 복사 선택 1건). D-132 시점 748건 / 65파일). Vitest + RTL — 스토어
- **화면 자동 점검(E2E, D-142)**: Playwright 8건(데스크톱·휴대폰 × 게스트 입장 + 세 게임 봇과 연습) — `npm --prefix client run e2e`, CI `bundle-jar` 잡이 묶은 jar 로 실행.
  리듀서, 족보 타입, 카드 에셋 매핑 등.
- 통합 테스트는 Docker 필요. 실행 명령은 `CLAUDE.md` "자주 쓰는 명령" 참조.
- **밀폐성(D-113)**: IT 는 Testcontainers 로 자기 Postgres/Redis 를 띄우고 compose 에 기대지
  않는다. 테스트 기본 주소를 `*.invalid` 로 막아, 컨테이너를 빠뜨린 IT 는 로컬에서도 CI 와
  똑같이 실패한다(이 가드 이전엔 CI 가 21회 연속 실패하는 동안 로컬은 그린이었다).

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
- 스컬킹 손패 dnd 정렬·특수 카드 SVG 에셋·i18n 이관 — S6 범위 밖(D-103 이월).
- JWT 리프레시 토큰(12h 단일 토큰, MVP 범위).
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
  **Row-Flow**(auto-fit 좌석 그리드 + 재생순 트릭 레일 — 좌석은 D-132 에서 테이블 배치로)로 폭 미디어 쿼리 0개. CSS 는
  `styles/parts/18-skullking-table.css` 에 `.sk-` 접두로 격리하고 네임스페이스 규칙을
  테스트로 기계화했다. 375×667 8인 실측 완료.
- **매치 영속·ELO(D-115)**: 게임별 전적 테이블로 해소 — §9.
- **라운드 결과·점수표(D-120)**: 서버가 라운드 사이에 멈추지 않으므로 직전 결과는 다음 라운드 예측
  중 비차단 패널(입력·손패 아래, 라운드별 닫기)로 보인다. `SkullKingMatchState.completedRounds`
  (`withRoundCompleted` 단일 경로) → `TableView.completedRounds`·`matchResult` 로 resync 복원. 헤더
  '점수표' Dialog(라운드×좌석). 이 세션에서 본 IN_GAME→FINISHED 면 게임판을 유지해 봇 방 라운드 10
  결과를 볼 수 있다.
- **봇(D-119)·튜토리얼(D-121)**: §6 · §11.
- **남은 것**: 끊김 유예 구간 정지(D-104 한계), 봇 상대 모델링(8인 대 최약수 대등). (티츄 봇 방 종료
  화면 유지는 D-240 으로 완료.)

---

## 17. 원카드 (S0~S5, D-123~D-131)

`domain.game.onecard` — 순수 룰 엔진(D-127) + 포트 어댑터·정의·라운드 시작·휴리스틱 봇·기록기 V12(D-128), 클라 게임판
`features/onecard`(D-129). 룰 정본 `docs/rules-onecard.md`, 설계·단계 `docs/plans/onecard.md`.

- **2~6인 개인전**, 54장(조커 2), 공격 누적·반격, 7 무늬 지정, K 한 번 더, 20장 파산, 1판 = 1라운드.
- **실시간 경쟁 창**: 1장 남으면 서버가 "원카드!/잡기!" 창(기본 3초)을 열고 전원에게 같은 무작위 위치를 준다. 판정은 방 액션
  락을 먼저 잡은 누름. 봇 누름·창 만료는 **포트 엔진 타이머**(`timer`/`onTimer`, D-128 — 유일한 포트 확장)가 서버에서 처리한다.
  인프라에 게임 이름 0건.
- **공개 전 보강(D-130)**: 진행 킥(재기동·타이머 유실 뒤 resync·구독 때 봇 루프·엔진 타이머 재무장), 경쟁 결과 로그, 기록 실패
  격리, 방 만들기 처음 선택 4명·턴 30초.
- **공개(D-131)**: 기본 `AVAILABLE`(되돌리기는 시크릿 `MIRBOARD_ONECARD_STATUS=COMING_SOON` — `docs/deploy.md`). 턴 카운트다운
  (resync `turnRemainingMs` + `TURN_CHANGED`·`PLAYER_ELIMINATED` 로 다시 세기, 경쟁 창 동안 숨김). 방 만들기에서 인원·턴 제한을
  생략하면 게임 선언(4명·30초).
- **검증**: 도메인 191건(Docker 불필요 187) — 2~6인 무작위 시뮬레이션·매 전이 불변식, 서버 경로 IT 10건(경쟁 창 6건·봇 풀매치 4건),
  엔진 타이머 단위 17건, 클라 게임판·스토어·튜토리얼(`npm --prefix client run test -- onecard`).
- **남은 것**: `docs/plans/onecard.md` §8·§9 남은 후속(메시지 순서, 강제 종료 기록 정책, 장기 정지 탈주화, 누름 하한 등).

---

> 최신 결정/번복은 `docs/decisions.md`, 진행 단계는 `docs/plans/mvp-roadmap.md` 가 정본.
> 본 문서와 어긋날 경우 그쪽을 신뢰한다.

---

*문서 생성: 코드베이스 정적 분석 기반(서버 프로덕션 210 파일 / 테스트 81 파일, 마이그레이션 V1~V10 확인).*
*최종 대조: 2026-08-01 (D-105, §12 배포·데모 계정 + 실측 수치 갱신). 본 문서는 정적 스냅샷이므로 `docs/plans/mvp-roadmap.md`·`docs/decisions.md`가 우선한다.*

## 18. 요트 (S0~S5, D-181~D-183·D-200)

- **룰**: 주사위 5개·차례마다 최대 3번 굴림·12칸(상단 63 이상 +35, 풀 하우스에 5개 같음 인정), 1~4인 순차 진행, 혼자 하기 가능(승패 없음 — 총점은 개인 최고 점수로, D-201).
  정본 `docs/rules-yacht.md`, 설계 `docs/plans/yacht.md`.
- **포트**: `GameContext.turnSeconds`(D-183) — 한 차례가 여러 액션이라 엔진이 차례 시작 시각을 상태에 두고 엔진 타이머로 차례 전체 마감을 건다
  (만기면 최고점 칸 자동 기록). 인프라 코드 0줄, `grep -rniE 'yacht|요트' …/infra` 0건. 비공개 뷰 없음.
- **봇**: 공개 정보만 보는 결정적 휴리스틱 — 주사위 묶음 252가지 점수를 미리 계산해 홀드 마스크 31개의 기대값을 전수로 본다. 1인 평균 177점
  (무작위 39 · 탐욕 78).
- **기록**: `yacht_match_results`·`yacht_match_participants` + `user_game_stats`(YACHT), 상대 전적은 최종 순위.
- **개인 최고 점수(D-201)**: 1인 판은 `yacht_solo_scores`(V17)에만, 최고 = 1인 판 + 끝까지 친 여럿 판 중 최고(게스트·봇 제외). 게임 중립 포트
  `BestScoreSource` → `GET /api/users/{id}/stats` 의 `games[].bestScore`. 게임판 종료 화면("새 최고 기록!")·프로필·대기실 참가자 카드.
- **동시 진행(D-202)**: 대기실 "진행 방식"(게임 중립 맵 옵션 `TURN_MODE`) — "모두 같이"면 라운드마다 모두 동시에 굴리고 전원 기록하면 다음 라운드
  (`ROUND_STARTED`), 제한은 라운드 전체 하나(만기면 아직인 사람 모두 자동 기록). 인프라 무변경(포트가 여러 대기 좌석을 이미 지원). 게임판은 내 주사위를
  가운데, 남의 주사위는 좌석 띠에 작게 실시간.
- **클라**: `features/yacht` — 주사위 판(실시간 홀드·굴림), 점수표(차례 열 후보 점수, 내 차례면 칸 눌러 기록), 차례 카운트다운(`tableView.turnRemainingMs`),
  홀드 모아 보내기·`BUSY` 재전송(D-200), 튜토리얼 6단계 + 퀴즈. e2e `practice.e2e.ts` 에 요트 포함(데스크톱·휴대폰).
- **테스트**: 요트 도메인 106건(점수 전수 7,776×12, 1~4인 시뮬레이션 각 2,000판) + Docker IT 5건(`YachtBotMatchSimulationIT`·`YachtRecorderFailureTest`),
  클라 요트 35건(게임판·스토어·튜토리얼·CSS).

## 19. 오목 (S0~S4, D-175~D-180·D-190)

- **룰**: 15×15, 1:1(2인)·2:2 팀전(4인 — 좌석 0·2 대 1·3, 좌석 +1 순으로 같은 팀이 같은 색을 번갈아). 방장이 대기실에서 룰을 고른다 — 렌주(기본,
  흑만 33·44·장목 금지, 흑 정확히 5·백 5 이상)·삼삼만 금지·자유 오목. 5목이 되는 수는 금수여도 승, 금수 자리는 서버가 계산해 공개 뷰·`TURN_CHANGED` 에
  싣는다(클라 이중 구현 없음). 시간 초과는 결정적 자동 한 수, 같은 사람 연속 2번이면 그 편 패. 기권·탈주는 그 편 패(2:2 는 팀 패), 무승부(판이 가득·흑에게
  금수만)는 ELO 없이 기록 행만. 정본 `docs/rules-omok.md`, 설계 `docs/plans/omok.md`.
- **포트(D-176 게임 중립 확장 3건)**: `Room.options` 맵(`opt:<NAME>`) · `GameDefinition.capacityFor/playersToStart(options)`(오목은 모드에 따라 2 또는 4,
  3명 시작 불가) · `GameContext.options` + 시작 이벤트 `GameStartingEvent.options`(S3b 보정). 상수 `TEAM_MODE`·`BOT_LEVEL`(게임 중립)·`OMOK_RULE`.
  비공개 뷰 없음(`Optional.empty()`), 엔진 타이머 없음. 인프라에 게임 이름 0건(`grep -rniE 'omok|오목' …/infra`).
- **봇(D-179)**: 쉬움(패턴 평가)·보통(필수수)·어려움(VCF + VCT + 안전 확인, 노드 상한 150,000) — 방 설정 `BOT_LEVEL` 하나가 방의 봇 전원에. 듀플리케이트
  200판: 보통 대 무작위 1.000 · 보통 대 쉬움 0.925 · 어려움 대 보통 **1.000**(D-191 — 칸 점수 표 + 반복 심화 알파베타 + 섞은 VCT, 이전 0.700, 테스트 기준 0.8). 시간(로컬 국면 1,000개) 어려움
  p99 36.7ms · 최대 노드 40,479.
- **기록**: `omok_match_results`·`omok_match_participants`(V15) + `user_game_stats`(OMOK — 팀 평균 ELO, 무승부는 `recordDraw` 로 행만), 상대 전적은
  상대 팀만.
- **클라(D-180)**: `features/omok` — SVG 판 하나, 두 단계 확정 착수(누르면 미리보기·다시 누르거나 [여기에 두기], 끌면 손가락 1.5칸 위, 데스크톱은
  한 번 클릭 설정), 차례 띠·카운트다운·기권·종료 카드·수 번호 토글, 튜토리얼 8단계 + 퀴즈 "어디가 금수일까?", CSS `21-omok-table.css`(`.om-`).
  대기실 설정 패널이 모드·룰·봇 난이도(봇이 앉은 방에서만)를 그린다. 메인 카드는 "2·4인 플레이"(D-196 — 카탈로그 `playerCounts`). e2e `practice.e2e.ts` 에 오목 포함.
- **공개(D-190)**: 기본 `AVAILABLE`(되돌리기는 시크릿 `MIRBOARD_OMOK_STATUS` — `docs/deploy.md`).
- **테스트**: 오목 도메인 144건(Docker 불필요 138 — 시뮬레이션 18,000판·금수 국면 31건·봇 강도 듀플리케이트 포함) + 서버 경로 IT 4건(`OmokServerFlowIT`)·
  기록 실패 격리 1건(`OmokRecorderFailureTest`), 클라 오목 33건(스토어 7·판 입력 9·게임판 9·튜토리얼 3·CSS 5).
- **남은 것**: 어려움 봇 0.8(역공 4 를 받는 VCT·평가 함수·렌주 흑 금수 유도), 운영 색별 승률(렌주 보통끼리 흑 15%), 돋보기.

## 20. 씨앗포 (S0~S5, D-220~D-226 · S6 실시간 이동·조준 D-228·D-229·D-250·D-251)

- **룰**: 하늘 정원 흙섬 위 화분 포대, 2~4인 개인전(엔진은 6인까지 검증), 차례당 한 발(이동은 발사에 실음), 각도·힘·바람(−10~10)·지형 파괴·낙하,
  체력 100, 콩알·호박탄(2발), 10 × 인원 차례 뒤 구름 밀물(판 길이 상한). 정본 `docs/rules-artillery.md`, 설계 `docs/plans/artillery.md`.
- **물리**: 서버가 한 발에 한 번 정수(Q16·삼각 정수표·SplitMix64)로 계산, 상태는 (시드, 구멍 목록)만 저장 — 실측 한 발 0.064ms(구멍 60개)·맵 0.34ms.
  클라는 대본을 재생만 한다(궤적 미리보기 없음).
- **포트**: 변경 0 — 발사 뒤 연출 대기는 엔진 타이머(D-128), 비공개 뷰 없음, 공개 뷰에 시드 없음. `grep -rni artillery …/infra` 0건.
- **봇**: "보통" — 같은 엔진으로 후보 발사를 시뮬레이션(예산 900회, 시간 아닌 횟수라 결정적) + 결정적 오차 ±2°·±2. 무작위 상대 60/60 승, 한 수 평균 2ms.
- **기록**: `artillery_match_results`·`artillery_match_participants`(V16) + `user_game_stats`(ARTILLERY), 상대 전적은 최종 순위.
- **클라**: `features/artillery` — Canvas 전장·대본 재생·끌어서 조준·조이스틱과 꾹 눌러 쏘는 발사 단추(D-253)·이동 미리보기·튜토리얼 6단계(S4 포대·특수탄·아이템 추가). e2e `practice.e2e.ts` 에 씨앗포(데스크톱·휴대폰).
- **연출(D-254)**: clay 연출 층 `skins/clayFx.ts`(`CLAY_FX`) — 탄별 꼬리·다층 폭발·파편 물리(`debris.ts`)·특수탄 연출·피해 비례 흔들림·피격 출렁임·바람 꽃가루·쓰러짐 꼬리·축포. 테스트 `artilleryFx.test.ts` 15건.
- **테스트**: 씨앗포 서버 55건(물리·맵·엔진·시뮬레이션 2~6인 × 200판·골든·비용·어댑터·봇 + Docker IT 5건), 클라 26건(지형·재생·스토어·게임판·튜토리얼·CSS).
- **S4 (D-224)**: 판 시작 **포대 고르기**(전원 동시 15초, 엔진 타이머 — 다 고를 때까지 숨김, 안 고르면 새싹포, 봇은 시드) · 포대 3종(새싹포 100/100/100% ·
  도토리포 120/70/60% · 민들레포 85/130/140%) · 고유 특수탄 판당 2발(덩굴탄 둔덕 쌓기 — 몸 자리는 비우고 그 목록을 지형 연산에 저장, 알밤탄 큰 구멍,
  홀씨탄 꼭대기 세 갈래) · 아이템 3종 전원 1개씩·한 차례 1개(물뿌리개 +25·바람개비·겹씨앗). 대본은 `stages[].shots`. 포트·인프라 변경 0, 새 Flyway 없음.
  S3 판의 물리는 비트 단위 그대로(구멍·포대·지형 해시 일치). 봇은 섞인 유닛으로도 무작위 상대 60/60, 봇끼리 유닛별 승률 새싹 11/24·도토리 12/24·민들레 13/24.
  테스트(D-224 시점) — 씨앗포 도메인 71건(Docker 불필요 69, S3 때 51)·서버 경로 IT 3·기록 실패 격리 1, 클라 36건(S3 때 26). 봇 예산 900 → 1,100회
  (바람개비 격자·탄/아이템 변형), 한 수 평균 2.3ms. 무작위 시뮬레이션 평균 차례 수 2인 11.3 · 3인 18.7 · 4인 25.5 · 5인 41.3 · 6인 46.9.
- **S5 (D-225·D-226)**: 방 설정 **모드**(`TEAM_MODE` 개인전 / 2:2 — 2:2 는 4명이 다 차야 시작, 팀 = 좌석 홀짝) · 아군 피해 있음 · 한 팀만 남으면 끝,
  동시 전멸은 탈락 직전 체력 합 → 같으면 무승부, 사람이 모두 탈락하면 생존 수 → 체력 합 · 팀 순위(이긴 팀 1위, 탈주자 최하위·패) · **팀 평균 ELO**
  (이긴 팀의 탈주자는 혼자 진 것으로), 무승부는 행만 · `V18` 참가자 `team` 열(상대 전적은 상대 팀만) · 봇은 아군을 겨냥하지 않고 아군 피해를 뺀다.
  **차례 = 포트리스식 딜레이**(개인전도 — 콩알 100·호박 130·특수 140·연료 2칸당 1·아이템 30/겹씨앗 60·넘김 80, 가장 작은 포대가 다음, 같으면 좌석 순서).
  상태 `turnOrder`(`SEAT`/`DELAY`)로 배포 전 판은 좌석 순서로 끝까지 — 좌석 순서 골든의 물리 지문이 main 037963e 와 같음을 고정. 클라: 좌석 팀 칩·⏱ 딜레이,
  다음 차례 줄, 조작부 ⏱ +N, 팀 결과(승·패·무). 포트·인프라 변경 0. 테스트(D-226 시점) — 씨앗포 도메인 90건(Docker 불필요 86)·서버 경로 IT 4
  (2:2 봇 완주 추가)·기록 실패 격리 1, 클라 42건. 시뮬레이션 평균 차례 수 개인전 2인 11.7 · 3인 20.4 · 4인 26.4 · 5인 38.8 · 6인 45.0, 팀전 2:2 24.7 · 3:3 41.5
  (팀 승 104:96 · 102:98, 각 200판 전부 상한 안). 봇 팀 vs 무작위 팀 30/30, 봇의 아군 피해 0(적 피해 3,285).
- **S6a 서버·포트 (D-228·D-229·D-250)**: 새 액션 **`MOVE(dx)`**(걸으면 바로 확정 — 같은 `Walker`, 공개 순번 `UNIT_MOVED`, 한 걸음도 못 가면
  `CANNOT_MOVE`, 걷다 떨어지면 비치명 → 차례 계속 / 치명 → 탈락·연출 대기·다음 차례) · 상태 `turnStartedAt`·`moveFuelUsed` · **차례 전체 마감**은
  AIMING 엔진 타이머(D-183 요트 방식, 만기 = 시간 초과 넘김) · 공개 뷰 엔진 `turnRemainingMs`·남은 `fuel` · 넘기기·시간 초과 딜레이 80 + 이동 딜레이 ·
  `FIRE.moveDx` 유지(봇·옛 클라 무변경). **포트 선택 확장 ⑧ `preview`**(기본 empty — 다른 여섯 게임 override 0) + 게임 중립
  `infra.ws.GamePreviewController`(`/app/room/{id}/preview`, 락 없음, 순번 없는 공개 이벤트만) → 씨앗포 `AIM_PREVIEW`(각도·쏠 수 있는 탄, 힘 없음) ·
  버킷 `game-preview` 10초 60, `game-action` 10초 30 → 60. 인프라 grep 0건, 물리 지문 무변경(JSON 골든은 두 필드를 빼면 S5 바이트 그대로), 새 Flyway 없음.
  테스트(D-250 시점) — 서버 전체 2,089건 `--rerun` 실패 0·건너뜀 6, 씨앗포 도메인 104건(+ 기록 IT 4)·서버 경로 IT 5(`ArtilleryLiveMoveIT` 추가)·기록
  실패 격리 1, 포트 기본값·미리보기 컨트롤러·라우팅 9건. 시뮬레이션(차례의 3분의 1 이 MOVE 조각) 2~6인·팀전 각 200판 전부 상한 안. **클라는 S6b**(스킨 층 D-227 뒤).
- **S6b 클라 (D-251)**: 걷기는 250ms 박자로 `MOVE` 하나씩(답이 와야 다음, BUSY 한 번 재전송, 거절·무응답·resync 면 서버 자리로) + 로컬 예측, [발사]는
  답 뒤에 남은 걸음을 `FIRE.moveDx` 로, [제자리]·유령 제거. 조준은 초당 4번 + 2초 유지 `/preview`(힘 없음). 남의 `UNIT_MOVED` 경로·`AIM_PREVIEW` 포신을 250ms 동안
  따라 그리고 "조준 중" 옆 탄 칩. 엔진 `turnRemainingMs`·남은 `fuel`, MOVE 탈락의 재생 없는 대기. 게임 밖은 `useStompRoom.sendPreview` 하나. 테스트(D-251 시점) —
  클라 전체 1,314건 / 110파일 실패 0(씨앗포 76건, S5 때 42), 브라우저 두 탭 실측(설계서 §11). 실측에서 꾹 누르기 낡은 클로저로 낭떠러지를 넘던 회귀를 잡아 고침.
- **휴대폰 화면 (D-253)**: 운영 피드백 반영 — 가로 모드(게임판이 화면을 덮고 전장이 높이에 꽉, 812×375 에서 667×375), 세로는 전장 폭 전체 + 위 하늘 여백
  100칸(375×812 에서 309×174 → 375×270), 바람·시간·차례·내 탄과 아이템 칩은 전장 위 HUD, 전장 밖은 고정 높이 한 줄 좌석 띠. 각도·이동·힘 버튼 무더기 →
  왼손 조이스틱(좌우 걷기·위아래 포신, 판정 순수 함수 `joystick.ts`) + 오른손 꾹 눌러 쏘는 발사 단추, 키보드 ←→↑↓·Space. 서버·프로토콜 무변경, 전송량은
  `MOVE` 박자당 하나(10초 ≤ 40)·미리보기 초당 4번 그대로(시험으로 고정). 클라 씨앗포 103건(D-252 때 88 — 조이스틱 판정 10·게임판 조작 5 추가).
