# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 현황

**Mirboard** — 웹 기반 턴제 보드게임 플랫폼. 공통 허브/로비 + **게임 2종**: 티츄(4인 2:2 팀전), 스컬킹(2~8인 개인전).

현재는 **동작하는 MVP** 상태이며 상용화 트랙(A/C/D/E/G) 진행 중이다(설계 Phase 1 ~ 클라 통합·UI 리디자인 Phase 20 완료, 이후 M0·M1·M3·M4·M5 완료·M2 진행 중(C3만 남음), 결정 이력 D-105까지). 로비/방 → 두 게임 풀게임 → 점수·ELO 영속(티츄만) → 봇 자동 채움 → 재접속/탈주 → 라이트/다크 UI 까지 end-to-end로 연결되어 있다. 멀티게임(트랙 E)은 **완료** — 포트 추출(D-98) 후 스컬킹을 룰 명세(D-100)·순수 엔진(D-101)·탈주(D-104)·인게임 배선(D-102)·클라 게임판(D-103)까지 붙였다. 스컬킹 매치 영속·ELO 는 `users.rating` 게임별 분리(D-02) 선행이라 의도적 별건.

- **서버** `server/` (Spring Boot 4 / Java 25, Gradle): 도메인 `domain.lobby`·`domain.game.{core,tichu,scoring}`, 인프라 `infra.{rest,ws,bot,messaging,metrics,config,web}`.
- **클라이언트** `client/` (Vite + React 18 + TS, Zustand, @stomp/stompjs, Tailwind+shadcn).
- **계약 문서(정본)**: `docs/api.md`(REST), `docs/stomp-protocol.md`(STOMP), `docs/redis-keys.md`(Redis), `docs/rules-tichu.md`(룰), `docs/game-port.md`(`GameEngine` 포트), `server/src/main/resources/db/migration/V*.sql`(Flyway V1~).
- **현황 단일 진실원**: `docs/implementation-status.md`(기능별 ✅ 표). 이력 `docs/decisions.md`, 로드맵 `docs/plans/mvp-roadmap.md`.
- **쇼케이스 문서(D-105)**: `README.md`(진입점), `docs/case-study-multi-game.md`(포트 서사).
  둘 다 **수치를 인용하고 재현 명령을 싣는다** — 코드가 바뀌어 수치가 흔들리면 두 문서를
  같이 고칠 것(케이스 스터디 §부록 명령을 돌려 대조). 실무 절차는 `CONTRIBUTING.md`(환경·
  테스트)·`docs/deploy.md`(배포)·`docs/qa-scenarios.md`(수동 검증).

상용화(포트폴리오 쇼케이스) 후속은 트랙 A(티츄 완성도)·C(운영 하드닝)·D(수평 확장성)·E(멀티게임)·G(문서·데모)의 마일스톤으로 진행 중 — `docs/plans/mvp-roadmap.md` 참조.

## Phase Gate 작업 규칙

**각 작업 단위(마일스톤) 완료 시 반드시 사용자 검토/승인을 받은 후 다음 단계로 진입한다.** 단계를 임의로 건너뛰지 말 것. 설계 변경을 동반하면 아래 "작업 체크리스트"의 docs-선행 절차(decisions.md D-NN 먼저)를 따른다.

초기 4-Phase(1 설계 → 2 로비 → 3 룰엔진 → 4 WS/클라)는 **전부 완료**, 이후 Phase 5~20(영속·배포·재접속·UI 등)까지 진행됐다(이력 `docs/decisions.md`, 현황 `docs/implementation-status.md`). 현재 후속은 상용화 트랙(A/C/D/E/G) 마일스톤으로 진행되며 동일한 Phase Gate 규칙을 적용한다. 상세는 `docs/plans/mvp-roadmap.md`.

## 절대 원칙 (위반 시 설계 무효)

### Server-Authoritative
- 셔플, 분배, 족보 판별, 점수 계산, 차례 결정은 **전부 서버**가 한다.
- 클라이언트가 보내는 `seq`, `capacity`, `cards` 등은 **검증 대상**이지 신뢰 대상이 아니다.
- 클라가 보낸 envelope의 `seq` 는 무시하고 서버는 `room:{id}:seq` INCR 결과를 단조 증가 카운터로 사용.

### State Hiding (상태 은닉)
- 본인 손패는 **`/user/queue/room/{roomId}` 큐로만** 전송. 절대 `/topic/room/{roomId}` 로 새지 않도록 한다.
- 직렬화 타입 자체를 분리한다: `TableView` (공개) vs `PrivateHand` (본인만). 같은 객체를 두 경로로 쓰지 말 것.
- 공개 상태에는 각자의 손패 **장수(int)만** 노출. 카드 목록 금지.

### 개인정보 최소화 (Schema-Level)
- `users` 테이블에 **추가 절대 금지** 컬럼: `email`, `phone`, `real_name`, `birth_date`, `address`, 기타 식별/연락 정보.
- 현재 허용 컬럼: `id`, `username`, `password_hash`, `win_count`, `lose_count`, `rating`(V2, D-02), `is_bot`(V3), `desert_count`(V4), `created_at`. `rating`/`is_bot`/`desert_count` 는 게임 성적·행동 집계용 derived 값이라 화이트리스트 추가 허용(`tier` 는 컬럼 아님 — rating 구간에서 계산). **내기 칩은 계정에 두지 않는다(D-82)** — 방 단위 테이블 칩(`room:{id}:chips`, Redis)으로만 존재. (`chip_balance` 컬럼은 D-81 에서 추가했다가 V7 에서 DROP.)
- 로그인/회원가입 엔드포인트도 헤더/쿠키 식별자를 기록하지 않는다 (IP는 인프라 레벨만).
- 선택적 코스메틱 아바타는 `users` 가 아니라 **별도 테이블 `user_avatars`**(V5, BYTEA 128px PNG)에 저장(D-80). `users` 화이트리스트는 불변. 조회는 공개 `GET /avatars/{userId}`, 업로드/삭제는 `POST`/`DELETE /api/me/avatar`(본인).

### 도메인 경계 (Modular Monolith)
- `domain.lobby` → `domain.game.tichu` 직접 의존 **금지**.
- 게임 디스패치는 반드시 `domain.game.core.GameRegistry` 를 거친다 (`GameDefinition` Bean 자동 수집).
- `domain.game.tichu` 는 `domain.game.core` 인터페이스만 의존.
- `infra/ws/*`, `infra/rest/*` 컨트롤러는 도메인 서비스를 호출만 한다. **룰 로직 금지**.
- **인게임은 `GameEngine` 포트만 본다 (D-98)**: 인프라는 `GameEngineProvider.forRoom(room)` →
  `GameRegistry.require(gameType).newEngine(ctx)` 한 경로로 엔진을 얻고 게임 이름을 쓰지 않는다.
  포트 계약 정본은 `docs/game-port.md`. 게임별 구현은 **2계층** — 순수 룰 엔진(`TichuEngine`,
  저장소 없음)을 포트 어댑터(`TichuGameEngine`)가 감싼다. 룰 단위 테스트가 Redis 를 끌고
  오지 않게 하려는 분리이므로 새 게임도 이 형태를 따를 것.
  유일한 예외: `RoomChipService` 는 칩 정산이 팀 승패에 묶여 있어 티츄를 직접 참조한다
  (칩은 포트 밖 — `docs/game-port.md` §2).
- **방 옵션도 게임이 선언한다 (D-106)**: `GameDefinition.supportedRoomOptions()` →
  `Set<RoomOption>`(`TARGET_SCORE`·`TEAMS`·`BETTING`). **기본은 빈 집합(옵트인)**이라 새 게임은
  아무것도 안 써야 맞다 — 안 쓰는 설정이 방 만들기·대기실에 뜨지 않는다. 티츄만 셋 다 선언.
  서버도 같은 집합으로 검증한다(미지원 옵션에 기본값 아닌 값 → `UNSUPPORTED_ROOM_OPTION`).
  모든 게임에 통하는 설정(인원·턴 제한·봇 채우기)은 여기 넣지 말 것.
- 새 게임 추가 절차: `domain.game.{newgame}` 패키지 + `GameDefinition @Component` Bean 등록
  (+ `GameEngine` 구현, `GameStartingEvent` 리스너로 라운드 시작) → 카탈로그/방 생성/인게임
  디스패치/봇/타임아웃/resync 가 자동 연결됨. 로비·허브 컨트롤러와 스케줄러 수정 불필요.
  (D-102 스컬킹이 이 약속을 실증 — REST/WS 컨트롤러·스케줄러·브로드캐스터·로비 **수정 0**.
  인프라 변경은 포트 계약 확장 1건(`desert` boolean→3치 `DesertOutcome`)뿐이고, 인프라에
  게임 이름 분기는 한 줄도 늘지 않았다. `grep -rn skullking .../infra` → 0건.)
- **클라 인게임도 게임 중립 (D-103)**: `useStompRoom` 은 게임 스토어를 import 하지 않고
  `RoomEventSink` 를 주입받는다(게임별 sink 파일이 스토어에 꽂는다). sink 는 **모듈 상수**여야
  하고 각 메서드는 **호출 시점에 `getState()`** 를 읽어야 한다(훅이 sink 를 ref 로 잡아
  effect deps 에서 빼기 때문). 게임판 분기는 `RoomPage` 의 IN_GAME 한 곳뿐이고, 각 게임판이
  자기 소켓·sink 를 소유해 다른 게임의 코드 경로는 실행되지 않는다.
- 정적·이미지 서빙 엔드포인트는 `/api/**` **밖**에 둔다 — `<img>`/브라우저 직접 요청은 Bearer 토큰(localStorage JWT)을 못 싣고, SecurityConfig 가 비-`/api` 를 default-permit. 민감 API 는 여전히 `/api/**` 하위(예: 아바타 조회 `/avatars/{userId}` 공개, 업로드 `/api/me/avatar` 인증).

## 기술 스택 결정사항

| 영역        | 결정                                                                                         |
|-----------|--------------------------------------------------------------------------------------------|
| JDK       | **Java 25 (LTS)** — Virtual Threads 기본 활성화 (`spring.threads.virtual.enabled=true`)         |
| Backend   | **Spring Boot 4.0.1** (Jakarta EE 11 / Spring Framework 7). `javax.*` 금지, `jakarta.*` 만 사용 |
| Build     | Gradle 9.4.1 (wrapper), Kotlin DSL                                                                   |
| Auth      | JWT HS256 12h, BCrypt. 시크릿은 `MIRBOARD_JWT_SECRET` 환경변수                                     |
| Migration | **Flyway** — JPA `ddl-auto` 사용 금지                                                          |
| Frontend  | Vite + React 18 + TypeScript, `@stomp/stompjs` + SockJS, `@dnd-kit`, Zustand. **Phase 20(D-76)**: Tailwind v3(`preflight:false`)+shadcn/ui(slate, CSS vars), 라이트/다크 토글(`themeStore`, `<html>.dark`, 기본 dark). shadcn 화면은 `.app-shell` 로 감싼다(스코프 base). 게임판 기하는 `styles/parts/*` 유지(D-94 분할). **게임별 게임판 CSS 는 신규 part + 접두 네임스페이스**(스컬킹 `.sk-`, D-103) — 공용 클래스 재정의 금지, `17-responsive.css` 는 계속 마지막. 게임판은 `.app-shell` 밖이라 tailwind border-box 리셋이 안 닿으니 스코프에서 명시할 것 |
| Data      | PostgreSQL 16 (영속, Phase 7-1 부터 D-39), Redis 7 (실시간 세션/방 상태)                                        |
| Test      | JUnit 5 + Mockito + Testcontainers / Vitest + RTL                                          |

### Java 25 / Spring Boot 4.0 활용 패턴
- `HandType`, `GameAction`, `GameEvent` 는 **sealed interface** 로 정의 → `switch` 표현식 패턴 매칭으로 누락 케이스를 컴파일러가 강제 검출.
- 상태 객체 (`TichuState`, `TrickState`, `Card`) 는 **record** + `with*` 메서드로 불변 전이.
- WebSocket/STOMP 핸들러는 가상 스레드 위에서 실행되어 동시 게임 수 증가 시에도 스레드 풀 고갈 없음.
- JPA `byte[]` 컬럼은 PostgreSQL 에서 `@JdbcTypeCode(SqlTypes.VARBINARY)` 명시(미지정 시 oid/Large Object 매핑 → `ddl-auto: validate` 기동 실패).
- JSON 으로 영속되는 record(예: `TichuMatchState`)에 필드 추가 시 compact constructor 에서 null 정규화(구 JSON 역직렬화 호환).

## 사용자 플로우

```
[로그인] → [미르보드카페(메인)] → [Room(대기실: 준비)] → [Game Table]
            └─ 게임 소개(요약+위키 "자세히") + 방 만들기 버튼/모달(게임 선택)
               + 열린 방 목록/입장 + 랭킹(GET /api/users/ranking) + 로비 채팅
```

- Phase 16(#7): 게임별 로비 페이지(`/games/:id/lobby`) 폐지 — 메인페이지로
  통합. 방 생성/입장/관전/랭킹/로비채팅이 모두 `/games` 한 화면.
- Phase 16(#2): 게임 시작 = 정원 4 + **전원 준비**(`POST /api/rooms/{id}/ready`,
  봇은 join 시 서버가 자동 ready). capacity 자동시작 폐기.
- 통합 대기실 채팅 (`/topic/lobby/chat`) 은 메인페이지 전역 채팅.
- 게임별 격리 채팅은 MVP 범위 밖.
- Phase 18(D-74): 메인 입장은 `join-or-reconnect`(멱등) — 나갔다 재입장
  ALREADY_IN_ROOM 버그 제거. `room_leave.lua` 가 빈 방 destroy 시
  `room:{id}:ready` 도 정리. 방 만들기는 버튼+모달. 메인 제목
  "미르보드카페", 게임 카드는 요약+외부 위키 "자세히" 링크(위키 URL 클라
  하드코딩, 카탈로그 API 무변경).
- Phase 19(D-75): 서버 STOMP Subscribe/Disconnect 후킹(in-memory
  `WsSessionRegistry`, 단일 인스턴스 전제). WAITING 끊김=즉시 leave(빈 방·
  관전자0 방 즉시 소멸). IN_GAME 탈주(명시 '나가기' / 끊김 후
  `mirboard.desertion.grace-seconds` 기본 120s(D-79, 모바일 관용; 구 30s) 미복귀)=상대팀 승리로
  매치 종료 + 탈주자 `desert_count`+1·lose+1·ELO−(봇 매치 ELO 제외,
  D-71). 탈주는 합성 `TichuMatchCompleted` 로 기존 `MatchResultRecorder`
  재사용. 패스 카드 선택 UI 는 `arena-actions` 로 통합(로직 불변).
- D-82(D-81 보정): **방 단위 테이블 칩 내기 모드**(계정 지갑 아님 — 칩을 계정에 안 둠).
  방 게임 시작 시 전원 동일 칩(`STARTING_STACK` 1000), 매치 종료마다 판돈
  `{0,10,50,100,500}`(0=없음)이 승팀↔패팀으로 이동(제로섬, 패자 보유분 한도 올인, 봇 매치
  제외). **'한 판 더'(리매치)** 로 같은 4명이 같은 테이블 계속 → 칩 누적, 판돈 미만 보유 시
  무료 재바이인, 방 나가면 칩 소멸. **stake>0 방은 봇 금지**. 칩은 `room:{id}:chips`
  (Redis HASH) + `CHIPS_SETTLED` 공개 이벤트(테이블 공개 정보), 정산은 신규 `RoomChipService`.
  매치 정상 종료는 FINISHED 대신 WAITING 복귀(리매치), 탈주는 FINISHED 유지. 판돈은 Redis
  room 해시 고정 → `TichuMatchCompleted.stake` 로 정산 전달.

## 자주 쓰는 명령

### 인프라 (현재 사용 가능)
```bash
# Postgres + Redis 기동
docker compose up -d postgres redis

# Flyway 마이그레이션 적용 (스키마 변경 시마다)
docker compose --profile migrate run --rm flyway

# 스키마 확인
docker compose exec postgres psql -U mirboard -d mirboard -c "\dt"
```

기본 자격증명 (개발 한정): Postgres `mirboard / mirboardpw`, DB `mirboard`, 포트 5432, Redis 비밀번호 없음.

### 서버 (Phase 2a~ 사용 가능)
처음 한 번만:
```bash
gradle wrapper --gradle-version 8.10.2   # 또는 docker run gradle:8.10.2-jdk21 ...
```
이후:
```bash
./gradlew :server:bootRun
./gradlew :server:test
./gradlew :server:compileJava -q   # 테스트 없이 빠른 컴파일 확인 (편집 중 점검)
# 커밋 시 pre-commit 훅 check:fast(클라 tsc + vitest + 서버 compile)가 자동 게이트.
# 새 마이그레이션/엔티티는 IT 1개(예: AuthFlowIntegrationTest) 기동으로 검증 —
# 풀 컨텍스트가 Flyway 적용 + Hibernate ddl-auto:validate 스키마 대조.
./gradlew :server:test --tests "com.mirboard.domain.lobby.auth.JwtServiceTest"
./gradlew :server:test --tests "com.mirboard.domain.lobby.auth.AuthServiceTest"
./gradlew :server:test --tests "com.mirboard.domain.game.core.GameRegistryTest"
./gradlew :server:test --tests "com.mirboard.domain.lobby.room.RoomServiceConcurrencyIT"
./gradlew :server:test --tests "com.mirboard.infra.rest.auth.AuthFlowIntegrationTest"
./gradlew :server:test --tests "com.mirboard.infra.rest.games.GameCatalogIntegrationTest"
./gradlew :server:test --tests "com.mirboard.infra.rest.rooms.RoomControllerIntegrationTest"
./gradlew :server:test --tests "com.mirboard.infra.ws.StompLobbyIntegrationTest"
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.card.*"
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.hand.*"
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.action.*"
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.scoring.*"
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.TichuEngineRoundSimulationTest"
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.TichuGameEngine*Test"   # 포트 어댑터 (D-98)
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.DealingLifecycleTest"
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.persistence.TichuMatchStateTest"
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.lifecycle.TichuRoundStarterIT"
./gradlew :server:test --tests "com.mirboard.infra.ws.GameStompControllerIntegrationTest"
./gradlew :server:test --tests "com.mirboard.infra.rest.rooms.RoomResyncIntegrationTest"
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.persistence.MatchResultRecorderIT"
```
통합 테스트는 Docker 가 떠 있어야 함 (Testcontainers 가 PostgreSQL 16 컨테이너를 띄움).

### 클라이언트 (Phase 4d~ 사용 가능)
```bash
# 처음 한 번
npm --prefix client install

# 개발 서버 (Vite, 기본 포트 5173, /api 와 /ws 는 8080 으로 proxy)
npm --prefix client run dev

# 타입 체크 + 프로덕션 빌드
npm --prefix client run build

# 단위 테스트 (Vitest + jsdom)
npm --prefix client run test
npm --prefix client run test -- authStore   # 특정 테스트만
```

## STOMP envelope 규약 (자주 참조됨)

```json
{ "eventId": "uuid", "seq": 42, "type": "PLAYED", "ts": 1715600000000,
  "payload": { ... } }
```

목적지는 게임별로 갈리지 않는다 — 서버가 방의 `gameType` 으로 역직렬화 타입을 고른다(D-98).
**이벤트 type 문자열은 게임 간 재사용된다**(`TURN_CHANGED` 등) — 토픽이 방 단위이고 방이
게임을 하나만 가지므로 충돌이 없다.

- 서버 → 클라 공개 `/topic/room/{roomId}`
  - 티츄: `PLAYED`, `PASSED`, `TURN_CHANGED`, `TRICK_TAKEN`, `TICHU_DECLARED`, `ROUND_ENDED`, `MATCH_ENDED` 등
  - 스컬킹(D-102): `BIDDING_STARTED`, `BID_SUBMITTED`(값 없음), `BIDS_REVEALED`, `PLAYING_STARTED`, `CARD_PLAYED`, `TURN_CHANGED`, `TRICK_TAKEN`, `ROUND_ENDED`, `SEAT_DESERTED`, `MATCH_ENDED`
- 서버 → 클라 비공개 `/user/queue/room/{roomId}`
  - 티츄: `HAND_DEALT`, `CARDS_RECEIVED`, `ERROR`
  - 스컬킹: `HAND_DEALT`, `ERROR`
- 클라 → 서버 `/app/room/{roomId}/action`
  - 티츄: `DECLARE_GRAND_TICHU`, `DECLARE_TICHU`, `READY`, `PASS_CARDS`, `PLAY_CARD`, `PASS_TRICK`, `MAKE_WISH`, `GIVE_DRAGON_TRICK`
  - 스컬킹: `PLACE_BID`, `PLAY_CARD`(티그리스는 `declaredAs`)

전체 카탈로그: `docs/stomp-protocol.md`.

## Redis 키 / 원자성

- `room:{id}` (HASH), `room:{id}:players` (LIST), `room:{id}:ready` (SET, Phase 16#2), `room:{id}:state` (JSON), `room:{id}:hand:{userId}` (JSON, 서버만), `room:{id}:seq` (INCR), `room:{id}:lock` (SET NX EX 2s).
- 방 입장/퇴장은 **Lua 스크립트로 원자화** — capacity 위반 0건 보장이 Phase 2 검증 기준.
- Phase 16(#2): 게임 시작은 `room_ready.lua` 가 전담 — 정원+전원 ready 시에만 WAITING→IN_GAME 원자 전이(중복 start 0건). `room_join.lua` 의 capacity 자동시작 제거.
- 액션 처리는 `room:{id}:lock` 으로 직렬화.

전체 표: `docs/redis-keys.md`.

## Plan / Memory / 이력관리 워크플로우

본 프로젝트는 초기 설계 변경이 잦아 추적을 형식화한다. 미래 Claude 세션은 아래 규칙을 따른다.

### 플랜 파일 — canonical 은 프로젝트 내부

- **단일 진실 공급원**: `docs/plans/mvp-roadmap.md` (그 외 새 플랜이 생기면 `docs/plans/` 아래 의미 있는 이름으로 추가).
- Plan Mode 가 자동 생성하는 `%USERPROFILE%\.claude\plans\*.md` 는 **임시 스크래치 패드** — 그대로 두면 다음 세션이 헷갈리므로, `ExitPlanMode` 직후 변경분을 `docs/plans/` 의 canonical 파일에 동기화한다.
- 새 Plan Mode 세션 시작 시 먼저 `docs/plans/mvp-roadmap.md` 를 읽고 현 상태를 파악한 뒤 스크래치에서 작업.

### Plan 모드를 쓰는 시점

- **Phase 전환 직전** (Phase N 완료 → N+1 진입).
- **설계 변경**: `docs/*.md` 또는 스키마(`V1__init.sql`) 수정을 동반하는 작업.
- **새 게임 도메인 추가** (`domain.game.{newgame}` 신설).
- **번복**: 이미 docs/CLAUDE.md/플랜에 적힌 결정을 뒤집어야 할 때.

단순 typo/한 줄 수정은 Plan 모드 불필요.

### Task 추적 (TaskCreate)

- 3개 이상의 명확한 단계가 보이는 작업은 **반드시 TaskCreate 로 분해**한다.
- 단계 시작 시 `in_progress`, 끝나는 즉시 `completed` (배치 처리 금지). 막혔으면 `in_progress` 유지 + 차단 사유를 새 task로 추가.

### Memory 사용 정책

- **저장 안 함**: docs/CLAUDE.md/README/decisions.md/플랜에 이미 적힌 모든 사실. 코드/git 으로 도출 가능한 사실.
- **사용자 메모리(user)**: 응답 스타일, 한국어 선호, 보고 톤 등의 개인 선호가 명확히 드러날 때.
- **피드백 메모리(feedback)**: 사용자가 명시적으로 교정하거나 비표준 접근을 승인했을 때 (이유와 함께).
- **프로젝트 메모리(project)**: 마감, 진행 중인 Phase 상태, 의사결정 백로그 등 docs로 만들기 애매한 휘발성 컨텍스트.

### 이력 관리 (Decision Log) — 단순 포맷

- 위치: `docs/decisions.md`.
- 형식: `## D-NN (YYYY-MM-DD) — 제목` + **짧은 문단(2~4문장)**. "무엇/왜" 가 한 문단에 같이.
- 번복/폐기는 항목 끝에 `*폐기 → D-XX*` 또는 `*변경 → D-XX*` 한 줄 추가 (삭제 금지).
- ID 는 단조 증가 (D-01, D-02, ...). 코드 작업 **전에** 항목을 먼저 추가.

### 작업 체크리스트 (설계 변경 동반 시)

1. `docs/decisions.md` 에 새 항목 추가 (또는 기존 항목에 폐기/변경 마커).
2. 영향받는 `docs/*.md` / `CLAUDE.md` / `README.md` / `docs/plans/*.md` 갱신.
3. (해당 시) 스키마/코드 변경.
4. TaskList 정리 (완료/폐기), 사용자에게 변경 요약 보고.

## 기타 운영 메모

- 한국어 사용자 — 응답/주석/문서는 한국어로 작성.
- 환경: macOS (`/Users/yupchang/Developer/mirboard`). 셸은 zsh, Bash 작업디렉토리는 호출 간 유지되므로 `npm --prefix client` 등은 repo 루트 절대경로에서 실행.
- 라우트 가드(ProtectedRoute)가 읽는 시작 상태는 `main.tsx` 에서 **렌더 전 동기** 복원(`init()`/`loadFromStorage()`) — useEffect 복원은 첫 렌더에서 `/login` 으로 튕기는 레이스. zustand 토글 스토어(theme/cardAnim/auth) 공통.
- Phase 1 산출물(`docs/*.md`, `V1__init.sql`)을 변경하려면 그 자체가 설계 변경. 코드보다 먼저 docs를 고치고 사용자 승인을 받을 것.
