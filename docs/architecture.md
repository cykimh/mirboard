# Mirboard 아키텍처

> 이 문서는 코드베이스 분석으로 작성된 **시스템 아키텍처 + 데이터 모델** 참조 문서입니다.
> 프로토콜·키·API의 세부 계약(contract)은 `docs/api.md`, `docs/stomp-protocol.md`,
> `docs/redis-keys.md` 가 단일 진실 공급원이며, 본 문서는 그 위의 구조/흐름을 설명합니다.
> 의사결정 이력은 `docs/decisions.md`(D-01~), 로드맵은 `docs/plans/mvp-roadmap.md` 참조.

---

## 1. 개요

**Mirboard** 는 웹 기반 턴제 보드게임 플랫폼이다. 공통 허브/로비 위에 게임을
플러그인처럼 얹는 **모듈러 모놀리스(modular monolith)** 구조이며, 1차 게임으로
티츄(Tichu)가 구현되어 있다.

| 영역 | 스택 |
|------|------|
| Backend | Java 25 (LTS, Virtual Threads), Spring Boot 4.0.1 (Jakarta EE 11 / Spring Framework 7) |
| Build | Gradle (Kotlin DSL), 멀티프로젝트(`server`) + npm 클라이언트 |
| Auth | JWT HS256 12h, BCrypt |
| Migration | Flyway (JPA `ddl-auto: validate`) |
| Frontend | Vite + React 18 + TypeScript, `@stomp/stompjs` + SockJS, `@dnd-kit`, Zustand, Tailwind v3 + shadcn/ui |
| Data | PostgreSQL 16(영속), Redis 7(실시간 세션/방 상태) |
| Test | JUnit 5 + Mockito + Testcontainers / Vitest + RTL |

서버 소스 **126개 클래스**, 테스트 **44개 클래스**. 클라이언트는 Vitest 단위 테스트 포함.

---

## 2. 절대 원칙 (설계 불변식)

아키텍처 전반을 지배하는 4개 원칙. 위반 시 설계 무효로 취급한다.

### 2.1 Server-Authoritative
셔플·분배·족보 판별·점수 계산·차례 결정은 **전부 서버**가 수행한다. 클라이언트가
보내는 `seq`, `capacity`, `cards` 등은 **검증 대상**이지 신뢰 대상이 아니다. 클라가
보낸 envelope의 `seq` 는 무시하고, 서버가 `room:{id}:seq` INCR 결과를 단조 증가
카운터로 사용한다.

### 2.2 State Hiding (상태 은닉)
본인 손패는 **`/user/queue/room/{roomId}` 큐로만** 전송한다. 직렬화 타입 자체를
분리하여(`TableView` 공개 / `PrivateHand` 본인만) 같은 객체가 두 경로로 새지 않게 한다.
공개 상태에는 각자의 손패 **장수(int)만** 노출하고 카드 목록은 금지한다.
관련 클래스: `domain.game.tichu.persistence.TableView`, `PrivateHand`.

### 2.3 개인정보 최소화 (Schema-Level)
`users` 테이블에 `email`/`phone`/`real_name`/`birth_date`/`address` 등 식별·연락
정보 컬럼을 **절대 추가하지 않는다**. 현재 화이트리스트는
`id, username, password_hash, win_count, lose_count, rating, is_bot, desert_count, created_at`.
`rating`/`is_bot`/`desert_count` 는 게임 성적·행동 집계용 derived 값이라 허용된 선례(D-02).

### 2.4 도메인 경계 (Modular Monolith)
- `domain.lobby` → `domain.game.tichu` **직접 의존 금지**.
- 게임 디스패치는 반드시 `domain.game.core.GameRegistry` 를 거친다 (`GameDefinition` Bean 자동 수집).
- `domain.game.tichu` 는 `domain.game.core` 인터페이스만 의존한다.
- `infra/ws/*`, `infra/rest/*` 컨트롤러는 도메인 서비스를 **호출만** 하고 룰 로직을 담지 않는다.
- 새 게임 추가: `domain.game.{newgame}` 패키지 + `GameDefinition @Component` 등록 →
  카탈로그·방 생성·엔진 디스패치가 자동 연결(로비/허브 컨트롤러 수정 불필요).

---

## 3. 모듈 구조

### 3.1 최상위 레이아웃

```
mirboard/
├── server/                  Spring Boot 백엔드 (Java 25)
├── client/                  Vite + React 클라이언트
├── docs/                    설계 계약 문서 (api/stomp/redis/decisions/plans/rules)
├── scripts/                 dev.sh / check.sh (인프라·서버·클라 오케스트레이션)
├── docker-compose.yml       PostgreSQL 16 + Redis 7 (+ flyway migrate 프로파일)
├── Dockerfile / fly.toml    배포 (Fly.io 멀티스테이지)
└── build.gradle.kts / settings.gradle.kts
```

### 3.2 서버 패키지 (`com.mirboard`)

```
domain/
├── lobby/
│   ├── auth/                User, JwtService, AuthService, UserRepository, BotUserRegistry, AuthException
│   └── room/                Room, RoomService, RoomRepository, RoomStatus, TeamPolicy,
│                            예외(RoomNotFound/RoomFull/AlreadyInRoom/NotHost/NotInRoom), Room*Event
└── game/
    ├── core/                GameRegistry, GameDefinition, GameEngine, GameContext, GameStatus,
    │                        GameAction, GameEvent, GameStartingEvent, GameNotFoundException
    ├── scoring/             EloCalculator (K=32, 봇 제외)
    └── tichu/               TichuGameDefinition, TichuEngine 외 다수
        ├── action/          TichuAction(sealed) + 8개 액션, ActionValidator, WishFulfillmentChecker
        ├── bot/             BotPlayer, RandomBotPlayer, LegalActionEnumerator
        ├── card/            Card, Suit, Special, Wish, Deck
        ├── event/           티츄 도메인 이벤트
        ├── hand/            Hand(sealed), HandDetector, HandComparator, HandType
        ├── invariant/       상태 불변식 검증
        ├── lifecycle/       TichuRoundStarter, TichuRoundEnder
        ├── persistence/     TichuGameStateStore, TichuStateMapper, TichuMatchState,
        │                    TichuMatchResult(JPA), TichuMatchParticipant(JPA), TableView, PrivateHand
        ├── scoring/         ScoreCalculator, RoundScore, MatchResultRecorder
        └── state/           TichuState(sealed: Dealing/Passing/Playing/RoundEnd),
                             PlayerState, TrickState, TichuDeclaration, Team, TurnManager

infra/
├── config/                  SecurityConfig, JwtAuthFilter, WebSocketConfig, RedisConfig, Jackson/Spa 등
├── rest/                    auth · games · me · rooms · users 컨트롤러
├── ws/                      GameStompController, RoomChatController, GameEventBroadcaster,
│   └── lobby/               LobbyStompController, WsSessionRegistry, 세션 라이프사이클·탈주 핸들러
├── bot/                     BotScheduler, TurnTimeoutScheduler
├── messaging/               MessageGateway(in-memory/redis), DomainEventBus, StompPublisher
├── metrics/                 MirboardMetrics (Prometheus)
└── web/                     GlobalExceptionHandler, ApiErrorEnvelope, JSON 401, MdcKeys
```

> 위 트리는 실제 디렉토리에서 확인된 패키지 기준이다. 클래스명 일부는 대표만 표기.

### 3.3 클라이언트 (`client/src`)

```
pages/        LoginPage, RegisterPage, GameHubPage(메인=미르보드카페), RoomPage(대기실+게임판)
features/
├── auth/     authStore (JWT 영속)
├── lobby/    CreateRoomModal, gameWiki(외부 위키 URL 하드코딩)
├── chat/     RoomChat, ArenaChatBubbles, roomChatStore
├── theme/    themeStore (라이트/다크 토글, <html>.dark, 기본 dark)
└── tichu/    GameTable, SortableHand, CardChip, SeatAvatar, MakeWishModal,
              GiveDragonTrickModal, EffectsOverlay, tichuStore, handType, useSfx
ws/           STOMP 연결/구독 훅
api/          REST 클라이언트
components/ui/ shadcn 프리미티브
types/ i18n/ lib/ styles/
```

상태는 Zustand 스토어로 분리: `authStore`, `tichuStore`, `roomChatStore`, `themeStore`, `effectStore`.

---

## 4. 런타임 아키텍처

### 4.1 REST 요청 흐름

```
HTTP 요청 (예: POST /api/rooms)
  → SecurityFilterChain → JwtAuthFilter (Authorization: Bearer 검증)
  → REST 컨트롤러 (infra.rest.*) — 요청 파싱, 도메인 서비스 호출만
  → 도메인 서비스 (RoomService/AuthService 등) — 비즈니스 로직 + Redis/Postgres
  → (예외 시) GlobalExceptionHandler → ApiErrorEnvelope {error:{code,message,details}}
  → HTTP 응답 (JSON)
```

전체 엔드포인트 계약은 `docs/api.md`. 컨트롤러는 룰/원자성 로직을 갖지 않고
도메인 서비스에 위임한다(§2.4).

### 4.2 STOMP/WebSocket 액션 흐름 (실시간)

엔드포인트 `ws://host/ws` (Spring STOMP + SockJS 폴백). CONNECT 헤더의 JWT를
채널 인터셉터가 검증한다.

```
클라 SEND  /app/room/{roomId}/action   { eventId, seq, type:"PLAY_CARD", payload:{...} }
  1. JWT 검증 (CONNECT 시 인증된 principal)
  2. GameStompController.@MessageMapping — TichuAction 다형 역직렬화, userId 추출
  3. room:{id}:lock 획득(SET NX EX 2s)으로 액션 직렬화, room:{id}:state(JSON) 로드
  4. ActionValidator — 현재 phase에 맞는 검증 (실패 시 lock 해제 + /user/queue 로 ERROR)
  5. TichuEngine.apply(state, seat, action) — 무상태 룰 적용 → (newState, events[])
  6. room:{id}:state 저장, room:{id}:seq INCR, lock 해제
  7. GameEventBroadcaster
       · 공개 이벤트 → /topic/room/{id}        (PLAYED, TURN_CHANGED, TRICK_TAKEN, ...)
       · 비공개 이벤트 → /user/queue/room/{id}  (HAND_DEALT, RESYNC)
       · 모든 메시지는 { eventId, seq(6단계 값), type, ts, payload } envelope 로 래핑
  8. 클라 리듀서(tichuStore)가 이벤트 적용 → 리렌더
```

envelope 규약과 토픽·큐·이벤트 카탈로그는 `docs/stomp-protocol.md` 가 단일 진실 공급원.

- 서버→클라 공개: `/topic/room/{id}`, `/topic/lobby/chat`, `/topic/lobby/rooms`, `/topic/room/{id}/chat`
- 서버→클라 비공개: `/user/queue/room/{id}` (HAND_DEALT, RESYNC, ERROR 등)
- 클라→서버: `/app/room/{id}/action`, `/app/lobby/chat`, `/app/room/{id}/chat`

### 4.3 게임 디스패치 (GameRegistry)

`GameRegistry` 는 Spring 컨텍스트의 모든 `GameDefinition` Bean을 자동 수집한다.
방 생성·카탈로그·엔진 인스턴스화가 전부 이 레지스트리를 경유하므로, 새 게임은
`GameDefinition @Component` 등록만으로 연결된다(로비/REST 코드 무변경). 현재 등록:
`TichuGameDefinition` (id `TICHU`, 4인, `AVAILABLE`).

### 4.4 봇 / 타임아웃 스케줄러

- `infra.bot.BotScheduler` — 봇 차례에 백그라운드로 합법 액션을 선택해 적용
  (`LegalActionEnumerator` 가 합법 수 열거). 딜레이는 `mirboard.bot.delay-millis`(기본 700ms),
  봇 구동 통합 테스트는 0으로 override.
- `infra.bot.TurnTimeoutScheduler` — 무응답 차례 자동 처리(자동 패스/스킵).

### 4.5 메시징 게이트웨이 (단일/멀티 인스턴스)

`mirboard.messaging.gateway` 설정으로 두 모드를 전환한다.

| 모드 | 값 | 동작 |
|------|----|------|
| 단일 인스턴스(기본) | `in-memory` | Spring ApplicationEvent + STOMP SimpleBroker (in-process) |
| 멀티 인스턴스(opt-in) | `redis` | Redis Pub/Sub fan-out, `DomainEventBus` 가 instanceId로 중복 제거 |

프로덕션(`application-prod.yml`)은 `redis` 를 강제. `infra/ws/WsSessionRegistry`(in-memory
세션→방 매핑)는 **단일 인스턴스 전제**(Phase 19, D-75)다.

---

## 5. 동시성 / 원자성

방 입장·퇴장·준비·시작 등 "전부 아니면 전무"여야 하는 연산은 Redis **Lua 스크립트**로
원자화한다. 스크립트는 `server/src/main/resources/lua/` 에 위치.

| 스크립트 | 역할 |
|----------|------|
| `room_create.lua` | 방 HASH + players LIST + open 인덱스 초기화 |
| `room_join.lua`   | status/capacity/멤버십 검증 후 player 추가 (capacity 자동시작 없음 — Phase 16) |
| `room_ready.lua`  | ready SET 토글, **정원+전원 ready 시에만** WAITING→IN_GAME 원자 전이(중복 start 0건) |
| `room_leave.lua`  | player 제거, 호스트 이양, 빈 방이면 `ready`·`spectators` 정리 후 소멸 |
| `room_delete.lua` | 방 관련 키 일괄 삭제(플레이어·관전자 0일 때) |
| `room_finish.lua` | IN_GAME→FINISHED 전이 (결과 화면 보존용 TTL) |

게임 액션 자체는 `room:{id}:lock` (SET NX EX 2s)으로 직렬화한다(§4.2). 동시성 검증
기준은 "capacity 위반 0건"이며 `RoomServiceConcurrencyIT` 가 이를 검사한다.

---

## 6. 데이터 모델

### 6.1 PostgreSQL (영속) — Flyway

마이그레이션: `server/src/main/resources/db/migration/`. JPA `ddl-auto: validate`
(스키마는 Flyway만 변경).

**users** (V1, +V2 rating, +V3 is_bot, +V4 desert_count)

| 컬럼 | 타입 | 제약 |
|------|------|------|
| `id` | BIGINT | PK, GENERATED ALWAYS AS IDENTITY |
| `username` | VARCHAR(20) | NOT NULL, UNIQUE (`uk_users_username`) |
| `password_hash` | VARCHAR(72) | NOT NULL (BCrypt; 봇은 로그인 불가 sentinel) |
| `win_count` | INT | NOT NULL DEFAULT 0 |
| `lose_count` | INT | NOT NULL DEFAULT 0 |
| `rating` | INT | NOT NULL DEFAULT 1000 (ELO, V2) |
| `is_bot` | BOOLEAN | NOT NULL DEFAULT FALSE (V3, 부분 인덱스 `idx_users_is_bot`) |
| `desert_count` | INT | NOT NULL DEFAULT 0 (탈주 누적, V4) |
| `created_at` | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP |

> `tier`(BRONZE/SILVER/.../MASTER)는 컬럼이 아니라 조회 시 `rating` 구간에서 계산되는 derived 값.
> V3는 시드 봇 4명(`bot_north/east/south/west`, 로그인 불가)을 INSERT 한다.

**tichu_match_results** (V1) — 매치 결과 영속

| 컬럼 | 타입 | 비고 |
|------|------|------|
| `id` | BIGINT | PK IDENTITY |
| `room_id` | VARCHAR(36) | 인덱스 `idx_match_room` |
| `finished_at` | TIMESTAMP | 인덱스 `idx_match_finished_at` |
| `team_a_score` / `team_b_score` | INT | NOT NULL |
| `payload_json` | TEXT | `TichuMatchState` 전체 JSON 스냅샷 |

**tichu_match_participants** (V1) — 매치-사용자 N:M

| 컬럼 | 타입 | 비고 |
|------|------|------|
| `match_id` | BIGINT | PK part, FK→`tichu_match_results(id)` |
| `user_id` | BIGINT | PK part, FK→`users(id)`, 인덱스 `idx_participant_user` |
| `team` | VARCHAR(1) | CHECK in ('A','B') |
| `is_win` | BOOLEAN | NOT NULL |

JPA 엔티티는 `TichuMatchResult`, `TichuMatchParticipant`(복합키). `User` 도 JPA 엔티티.
방 메타데이터는 JPA가 아니라 Redis에만 둔다(휘발).

### 6.2 Redis (실시간) — 키 구조

전체 타입/TTL/원자성 표는 `docs/redis-keys.md`. 핵심 키:

| 키 | 타입 | 용도 |
|----|------|------|
| `room:{id}` | HASH | 방 메타(host, name, gameType, status, capacity, teamPolicy 등) |
| `room:{id}:players` | LIST | 입장 순 userId |
| `room:{id}:ready` | SET | 준비 완료 userId (Phase 16) |
| `room:{id}:spectators` | SET | 관전자 userId |
| `room:{id}:state` | STRING(JSON) | `TichuState` 직렬화 (서버만) |
| `room:{id}:hand:{userId}` | STRING(JSON) | `PrivateHand` 캐시 (서버만, resync 빠른 경로) |
| `room:{id}:seq` | STRING(INCR) | 단조 증가 이벤트 카운터 |
| `room:{id}:lock` | STRING | 액션 직렬화 락 (SET NX EX 2s) |

`room:{id}:state` 는 모든 손패를 포함하는 서버 전용 상태이며, 여기서 각 사용자
손패만 추출해 `/user/queue` 로 보낸다(§2.2). 공개 `TableView` 에는 장수만 노출.

---

## 7. 횡단 관심사

- **인증**: `JwtAuthFilter`(REST) + STOMP CONNECT 인터셉터. JWT HS256 12h,
  시크릿 `MIRBOARD_JWT_SECRET`(≥32바이트).
- **예외 처리**: `GlobalExceptionHandler`(@ControllerAdvice) 가 도메인 예외 →
  `ApiErrorEnvelope` 로 변환. 401은 JSON.
- **관측성**: Micrometer + Prometheus(`/actuator/prometheus`), `MirboardMetrics` 카운터.
  로그는 MDC(`userId`/`roomId`/`eventId`) 자동 부착(`MdcKeys`).
- **재접속/탈주(D-74/D-75)**: WAITING 끊김=즉시 leave. IN_GAME 탈주(명시 '나가기' 또는
  끊김 후 `mirboard.desertion.grace-seconds` 기본 30s 미복귀)=상대팀 승리로 매치 종료 +
  탈주자 `desert_count`+1·lose+1·ELO−(봇 매치 제외). 합성 `TichuMatchCompleted` 로
  `MatchResultRecorder` 재사용. resync 는 `GET /api/rooms/{id}/resync` 가 tableView +
  privateHand + seq 제공.

---

## 8. 환경 설정 (기본값 / override)

`server/src/main/resources/application.yml` (개발) / `application-prod.yml` (Fly.io) 기준.

| 설정 | 기본값 | 환경변수 |
|------|--------|----------|
| JWT 시크릿 | dev sentinel | `MIRBOARD_JWT_SECRET` (≥32B) |
| JWT 만료 | 12h | — |
| DB URL | `jdbc:postgresql://127.0.0.1:5432/mirboard` | `MIRBOARD_DB_URL` (또는 HOST/PORT/NAME/USER/PASSWORD 조각) |
| Redis | `127.0.0.1:6379`, no pw, no TLS | `MIRBOARD_REDIS_HOST/PORT/PASSWORD/SSL` |
| 메시징 모드 | `in-memory` | `MIRBOARD_MESSAGING_GATEWAY` (`redis`) |
| 봇 딜레이 | 700ms | `MIRBOARD_BOT_DELAY_MILLIS` |
| 탈주 유예 | 30s | `mirboard.desertion.grace-seconds` |
| 서버 포트 | 8080 | `MIRBOARD_PORT` |
| Virtual Threads | enabled | `spring.threads.virtual.enabled` |

로컬 인프라 자격증명(개발 한정): Postgres `mirboard/mirboardpw`, DB `mirboard`, Redis 무인증.

---

## 9. 빌드 / 실행 / 배포

```bash
# 인프라
docker compose up -d postgres redis
docker compose --profile migrate run --rm flyway   # 스키마 적용

# 서버
./gradlew :server:bootRun        # 개발 (8080)
./gradlew :server:test           # 단위 + 통합(Testcontainers, Docker 필요)

# 클라이언트
npm --prefix client install
npm --prefix client run dev      # Vite 5173 → /api,/ws 8080 proxy
npm --prefix client run build    # 타입체크 + 프로덕션 빌드
npm --prefix client run test     # Vitest
```

프로덕션은 `client` 빌드 산출물을 서버 정적 리소스로 번들해 단일 jar로 서빙하고,
`Dockerfile`/`fly.toml` 멀티스테이지로 Fly.io 에 배포한다.

---

*문서 생성: 코드베이스 정적 분석 기반. 룰 세부(족보 종류·점수 규칙·특수 카드 상호작용)는*
*`docs/rules-tichu.md`, 메시지 계약은 `docs/stomp-protocol.md`, REST는 `docs/api.md` 를 정본으로 본다.*
