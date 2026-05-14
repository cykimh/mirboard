# Mirboard - 보드게임 플랫폼 MVP 계획

## Context
- **목표**: 웹 기반 턴제 보드게임 **플랫폼** MVP. 여러 보드게임을 호스팅할 수 있는
  공통 허브를 갖추되, 첫 출시 게임으로 티츄를 구현한다.
- **현재 상태**: `C:\workspace\mirboard\` 는 비어있는 그린필드. 모든 코드 신규 작성.
- **핵심 비기능 요구**:
  - Server-Authoritative: 클라는 입력기/뷰어. 셔플/분배/족보검증/점수계산은 전부 서버.
  - State Hiding: 본인 손패는 본인 큐로만, 공개 정보는 토픽으로.
  - 개인정보 최소: `username`, `password_hash`, 전적만. email/phone/실명 등 컬럼 추가 금지.
  - **게임 확장성**: 새 보드게임을 추가하려면 `domain.game.{newgame}` 패키지만 추가하고
    `GameDefinition` Bean을 등록하면 카탈로그/방 생성/룰 디스패치가 자동으로 연결되어야
    한다. 로비/허브 코드는 수정 불필요.

## 0. 사용자 플로우 (UX Flow)

```
[Login]
   ↓ (성공 → JWT 저장)
[Game Hub]      ← /api/games 로 카탈로그 표시 (티츄: AVAILABLE, 그 외: COMING_SOON)
   ↓ (게임 카드 클릭)
[Game Lobby]    ← /api/rooms?gameType=TICHU&status=WAITING — 방 목록 + 생성 + 통합 채팅
   ↓ (방 입장 또는 생성)
[Waiting Room]  ← 4/4 모이면 자동으로 게임 시작
   ↓
[Game Table]    ← 티츄 플레이
```

- **Game Hub** 는 "어떤 게임을 할지" 선택하는 단일 화면. 카탈로그는 서버의
  `GameRegistry` 가 진실 공급원.
- **Game Lobby** 는 선택된 게임 타입으로 필터된 방 목록 화면. URL: `/games/tichu/lobby`.
- **통합 대기실 채팅** (`/topic/lobby/chat`) 은 Hub/Lobby 어느 화면에서든 동일하게
  보이는 플랫폼 전역 채팅. 게임별 격리 채팅은 MVP 범위 밖.

## 1. 기술 스택 결정 (defaults — 이의 없으면 채택)

| 영역 | 선택 | 이유 |
| --- | --- | --- |
| Build | **Gradle (Kotlin DSL) 8.10+** | Spring Boot 4.0 + Java 25 toolchain 지원 |
| Backend | **Java 25 (LTS), Spring Boot 4.0.1** (Web, WebSocket, Security, Data JPA, Data Redis), `lettuce` | Jakarta EE 11 / Spring Framework 7 베이스 |
| Auth | JWT (HS256, 12h), BCrypt | stateless, REST/WS 양쪽 재사용 |
| Migration | **Flyway** | JPA `ddl-auto`는 처음부터 금지 |
| Frontend | **Vite + React 18 + TS** (SPA) | 게임에 SSR 불필요, 빠른 HMR |
| WS Client | `@stomp/stompjs` + SockJS fallback | Spring STOMP와 표준 |
| DnD | `@dnd-kit` | 지침 명시 |
| 상태관리 | Zustand + React Query | 보일러플레이트 최소 |
| Test (BE) | JUnit 5 + Mockito + Testcontainers(MySQL/Redis), Spring Boot Test 4.0 | Java 25 toolchain 동일 |
| Test (FE) | Vitest + React Testing Library | |
| Local Dev | **docker-compose** (MySQL 8, Redis 7) | 1 명령으로 의존성 기동 |

### 1.1 Java 25 / Spring Boot 4.0 활용 포인트 (Phase 2~3 구현 가이드)

- **Virtual Threads 기본 활성화** — `spring.threads.virtual.enabled=true`. STOMP
  메시지 핸들러와 REST 컨트롤러 모두 가상 스레드 위에서 실행되어 동시 게임 수가
  늘어도 OS 스레드 풀 고갈 없이 처리. (`Tomcat` 도 가상 스레드 executor 지원.)
- **Sealed types + pattern switch (Java 21+ 표준화)** — `HandType`, `GameAction`,
  `GameEvent` 는 `sealed interface` 로 모델링하고 `switch` 표현식의 패턴 매칭으로
  분기. 룰 엔진의 누락 케이스를 컴파일러가 잡아준다.
  ```java
  return switch (action) {
    case PlayCardAction p -> apply(state, p);
    case PassAction      _ -> applyPass(state);
    case DeclareTichu    d -> declareTichu(state, d);
    // 새 액션 추가 시 컴파일 에러로 강제 검출
  };
  ```
- **Records + with-pattern** — `TichuState`, `TrickState`, `Card` 는 record로.
  불변 상태 전이 (`state.withTurn(next)`) 패턴이 자연스럽게 적용된다.
- **Jakarta EE 11 baseline** — 모든 import는 `jakarta.*`. `javax.*` 사용 금지
  (Spring Boot 4.x 는 처음부터 jakarta 만 지원).
- **Spring Security 7** — `SecurityFilterChain` lambda DSL 의 deprecation 정리됨.
  `httpSecurity.with(new MyConfigurer(), Customizer.withDefaults())` 형태 사용.
- **`RestClient` / `HttpServiceProxyFactory`** — 외부 호출이 생기면 `RestTemplate`
  대신 사용. (현재 MVP 범위에선 외부 호출 없음.)

## 2. 디렉토리 구조 (초기 스캐폴드)

```
mirboard/
├── docker-compose.yml          # mysql, redis
├── README.md
├── server/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/com/mirboard/
│       │   ├── MirboardApplication.java
│       │   ├── domain/
│       │   │   ├── lobby/           # 회원/방/채팅
│       │   │   │   ├── auth/        # JwtService, AuthService, UserEntity
│       │   │   │   ├── room/        # RoomService, RoomRepository(Redis)
│       │   │   │   └── chat/        # LobbyChatController
│       │   │   └── game/
│       │   │       ├── core/        # GameDefinition, GameRegistry,
│       │   │       │                  GameEngine, TurnManager, ActionValidator,
│       │   │       │                  GameAction, GameEvent (sealed interfaces)
│       │   │       └── tichu/       # TichuGameDefinition (@Component) + TichuEngine + 룰
│       │   │           ├── card/    # Card, Deck, SpecialCard
│       │   │           ├── hand/    # HandType, HandDetector, HandComparator
│       │   │           ├── state/   # TichuState, TrickState, PrivateHand, TableView
│       │   │           ├── scoring/ # ScoreCalculator
│       │   │           └── action/  # PlayCardAction, PassAction, ... + validators
│       │   └── infra/
│       │       ├── config/          # SecurityConfig, WebSocketConfig, RedisConfig
│       │       ├── rest/            # AuthController, GameCatalogController,
│       │       │                      RoomController, StatsController
│       │       ├── ws/              # GameStompController, EventPublisher
│       │       └── persistence/     # JpaRepositories, Flyway migrations
│       └── test/java/...            # 도메인별 단위 테스트
└── client/
    ├── package.json, vite.config.ts, tsconfig.json
    └── src/
        ├── app/                     # Router, App shell
        ├── pages/                   # LoginPage, GameHubPage, LobbyPage,
        │                              RoomPage, GamePage
        ├── features/
        │   ├── auth/                # api, store, hooks
        │   ├── games/               # 카탈로그 fetch, 게임 카드 UI
        │   ├── lobby/               # 방 목록/생성/채팅 (게임별)
        │   └── tichu/               # board, hand, controls, dnd
        ├── ws/                      # useStompRoom, useLobbyStomp
        ├── api/                     # REST client (fetch wrapper + JWT)
        └── types/                   # 서버와 공유하는 이벤트 페이로드 타입
```

**경계 규칙**
- `domain.lobby` ↛ `domain.game.tichu` 직접 의존 금지. 게임 디스패치는 반드시
  `domain.game.core.GameRegistry` 를 통한다.
- `domain.game.tichu` 는 `domain.game.core` 의 인터페이스만 의존.
- `infra/ws/*` 컨트롤러는 도메인 서비스를 호출하기만 함 (룰 로직 금지).
- 새 게임 추가 절차: ① `domain.game.{newgame}` 패키지 신설 → ② `GameDefinition`
  Bean + `GameEngine` 구현체 등록 → ③ 클라이언트 `features/{newgame}` 추가.
  로비/허브/REST 컨트롤러는 **수정 없이** 자동 노출.

## 2.1 GameRegistry / GameDefinition 패턴

```java
public sealed interface GameDefinition
        permits TichuGameDefinition /* future games here */ {
    String id();                       // "TICHU", "GO", ...
    String displayName();              // "티츄"
    String shortDescription();
    int minPlayers();
    int maxPlayers();
    GameStatus status();               // AVAILABLE | COMING_SOON | DISABLED
    GameEngine newEngine(GameContext ctx);
}

@Component
public class GameRegistry {
    private final Map<String, GameDefinition> byId;
    GameRegistry(List<GameDefinition> defs) {
        this.byId = defs.stream()
            .collect(toUnmodifiableMap(GameDefinition::id, identity()));
    }
    public List<GameDefinition> available()   { /* status==AVAILABLE 만 */ }
    public List<GameDefinition> catalog()     { /* COMING_SOON 포함 전체 */ }
    public GameDefinition require(String id)  { /* missing → NOT_FOUND */ }
}
```

- 방 생성 시 `RoomService` 는 `gameType` 을 `GameRegistry.require()` 로 검증 후
  capacity = `definition.maxPlayers()` 로 설정.
- 게임 시작 시 `definition.newEngine(ctx)` 로 엔진 인스턴스 생성.
- 카탈로그 정렬: AVAILABLE 먼저, 그 안에서 displayName 알파벳/가나다 순.

---

## Phase 1 — DB / API / 프로토콜 설계

### 1.1 MySQL 스키마 (Flyway `V1__init.sql`)

```sql
CREATE TABLE users (
  id            BIGINT      PRIMARY KEY AUTO_INCREMENT,
  username      VARCHAR(20) NOT NULL UNIQUE,        -- ID 겸 닉네임 (영숫자 3~20)
  password_hash VARCHAR(72) NOT NULL,                -- BCrypt
  win_count     INT         NOT NULL DEFAULT 0,
  lose_count    INT         NOT NULL DEFAULT 0,
  created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tichu_match_results (
  id           BIGINT      PRIMARY KEY AUTO_INCREMENT,
  room_id      VARCHAR(36) NOT NULL,
  finished_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  team_a_score INT         NOT NULL,
  team_b_score INT         NOT NULL,
  payload_json JSON        NOT NULL                   -- 참가자/세부 점수 등 (정규화는 추후)
);

CREATE TABLE tichu_match_participants (
  match_id BIGINT NOT NULL,
  user_id  BIGINT NOT NULL,
  team     CHAR(1) NOT NULL,                          -- 'A' | 'B'
  is_win   BOOLEAN NOT NULL,
  PRIMARY KEY (match_id, user_id),
  FOREIGN KEY (match_id) REFERENCES tichu_match_results(id),
  FOREIGN KEY (user_id)  REFERENCES users(id)
);
```

**절대 추가 금지**: `email`, `phone`, `real_name`, `birth_date`, `address`.

### 1.2 Redis 키 설계 (모두 `EXPIRE` 적용)

| 키 | 타입 | TTL | 설명 |
| --- | --- | --- | --- |
| `room:{id}` | HASH | 6h | `hostId, gameType, status(WAITING/IN_GAME/FINISHED), capacity, createdAt` |
| `room:{id}:players` | LIST(순서) | 6h | 입장 순서 (4명 cap) |
| `rooms:open` | ZSET | - | 대기방 목록 (score = createdAt) |
| `room:{id}:state` | STRING(JSON) | 6h | TichuState 전체 마스터 스냅샷 (서버만 접근) |
| `room:{id}:hand:{userId}` | STRING(JSON) | 6h | 해당 유저 손패 (서버만 접근, 클라엔 큐로 push) |
| `room:{id}:lock` | STRING | 30s | 입장/액션 원자화용 (SETNX) |
| `session:{userId}` | HASH | 30m | `currentRoomId, lastSeenAt, wsSessionId` |
| `presence:lobby` | SET | - | 로비 접속자 |

방 입장은 **Redis Lua 스크립트**로 원자화: capacity 체크 → `players` push → `room:{id}` 갱신을 한 트랜잭션.

### 1.3 REST API 명세

| Method | Path | Body / Query | 응답 | 인증 |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/register` | `{username, password}` | `{userId, username}` | × |
| POST | `/api/auth/login` | `{username, password}` | `{accessToken, expiresAt}` | × |
| GET  | `/api/me` | - | `{userId, username, winCount, loseCount}` | ○ |
| GET  | `/api/games` | - | `Game[]` (카탈로그, status 포함) | ○ |
| GET  | `/api/rooms` | `?gameType=TICHU&status=WAITING` | `Room[]` (요약) | ○ |
| POST | `/api/rooms` | `{name, gameType:"TICHU"}` | `Room` | ○ |
| POST | `/api/rooms/{id}/join` | - | `Room` | ○ |
| POST | `/api/rooms/{id}/leave` | - | 204 | ○ |
| GET  | `/api/rooms/{id}/resync` | - | `{tableView, privateHand, phase, currentTurnUserId, eventSeq}` | ○ (참여자만) |

### 1.4 STOMP 페이로드 규격

- **구독 토픽**
  - `/topic/lobby/chat` — 로비 채팅 (텍스트)
  - `/topic/lobby/rooms` — 방 목록 변경 알림
  - `/topic/room/{roomId}` — 방 내 공개 이벤트
  - `/user/queue/room/{roomId}` — 본인 전용 (손패, 권한 에러)
- **클라이언트 발행**
  - `/app/lobby/chat` — `{message}`
  - `/app/room/{roomId}/chat` — `{message}`
  - `/app/room/{roomId}/action` — 게임 액션
- **공통 envelope**
  ```json
  { "eventId": "uuid", "seq": 42, "type": "PLAYED", "ts": 1715600000,
    "payload": { ... } }
  ```
- **이벤트 타입 (서버→클라 공개)**: `ROOM_UPDATED`, `GAME_STARTED`, `HAND_DEALT_COUNT`, `CARDS_PASSED`, `PLAYED`, `PASSED`, `TURN_CHANGED`, `TRICK_TAKEN`, `WISH_MADE`, `TICHU_DECLARED`, `ROUND_ENDED`, `GAME_ENDED`, `PLAYER_DISCONNECTED`, `PLAYER_RECONNECTED`, `ERROR`.
- **이벤트 타입 (서버→클라 비공개)**: `HAND_DEALT`(8장), `HAND_DEALT_FULL`(14장), `RESYNC`.
- **액션 타입 (클라→서버)**: `DECLARE_GRAND_TICHU`, `DECLARE_TICHU`, `PASS_CARDS`(좌/우/파트너 각 1장), `PLAY_CARD`, `PASS`, `MAKE_WISH`(소원), `GIVE_DRAGON_TRICK`(상대팀 지정).

**Phase 1 산출물 = 본 섹션 + `docs/api.md`, `docs/stomp-protocol.md`, `docs/redis-keys.md`** 작성하고 사용자 승인 요청.

---

## Phase 2 — 로비 모듈

- **회원가입**: username 정규식 `^[A-Za-z0-9_]{3,20}$`, password 길이 8~64. BCrypt cost 10.
- **로그인**: JWT (`sub=userId`, `username`, `iat`, `exp`). 시크릿은 `application.yml` 외부 (`MIRBOARD_JWT_SECRET`).
- **Spring Security**: `SecurityFilterChain` stateless + JWT 필터 + STOMP `ChannelInterceptor`로 CONNECT 시 토큰 검증.
- **RoomService**
  - `createRoom`, `joinRoom`, `leaveRoom`: 모두 Lua 스크립트 원자 처리.
  - 4번째 입장 시 `status=IN_GAME` 으로 전이 + `GameEngine.start(roomId)` 호출.
  - 호스트 퇴장 시 다음 입장자 승격, 전원 퇴장 시 `room:*` 삭제.
- **통합 대기실 채팅**: 단순 STOMP relay, 메모리/Redis Pub-Sub 채널 선택은 단일 인스턴스 가정으로 in-memory.

**검증**: 4명 동시 입장 요청을 보내 capacity 5 이상 들어가는 케이스 0건 (통합 테스트, Testcontainers Redis).

### Phase 2 — 단계별 구현 로드맵 (review-able chunks)

Phase 2 는 한 번에 모든 파일을 만들지 않고 **검토 가능한 5개 청크**로 쪼개서 진행한다.
각 청크 종료 시 사용자에게 요약 보고 + 다음 청크 진입 동의.

#### Phase 2a — Gradle / Spring Boot 부트스트랩  ✅ 완료
- **목표**: `gradle :server:bootRun` 으로 빈 Spring Boot 가 떠서 MySQL/Flyway/Redis
  와 정상 통신함을 확인. 도메인 로직 0.
- **신규 파일**
  - `settings.gradle.kts` (root) — `rootProject.name="mirboard"`, `include("server")`
  - `server/build.gradle.kts` — Spring Boot 4.0.1, Java 25 toolchain, 필요한 starter
    (`web`, `websocket`, `security`, `data-jpa`, `data-redis`, `validation`,
    `flyway-core`, `flyway-mysql`, `mysql-connector-j`, `jjwt 0.12.x`).
  - `server/gradle.properties` — `org.gradle.parallel=true`, caching 옵션.
  - `server/src/main/java/com/mirboard/MirboardApplication.java`
  - `server/src/main/resources/application.yml` — DataSource/Redis/Flyway/JWT/가상스레드.
  - (선택) `gradle/wrapper/gradle-wrapper.properties` — 8.10 명시.
- **부트스트랩 메모**: `gradle-wrapper.jar` 는 바이너리라 본 도구로 생성 불가. 사용자가
  한 번 `gradle wrapper --gradle-version 8.10` 실행 (또는 Docker로
  `docker run --rm -v $PWD/server:/w -w /w gradle:8.10.0 gradle wrapper`).
- **검증**: `gradle :server:bootRun` → `Started MirboardApplication`, Flyway 가
  `flyway_schema_history` 갱신. Actuator 없이도 8080 떠야 함.

#### Phase 2b — 인증 (Auth)  ✅ 완료
- **목표**: `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/me` 가
  실제로 동작. BCrypt 해시 검증. JWT 발급/파싱.
- **패키지 / 신규 파일**
  - `com.mirboard.domain.lobby.auth/`
    - `User.java` — `@Entity`, fields: id, username, passwordHash, winCount,
      loseCount, createdAt. 정적 팩토리 `User.create(username, passwordHash)`.
    - `UserRepository.java` — `extends JpaRepository<User, Long>`, `findByUsername`.
    - `UsernamePolicy.java` — 정규식 검증 (`^[A-Za-z0-9_]{3,20}$`).
    - `PasswordPolicy.java` — 길이 8~64.
    - `AuthService.java` — `register(username, password)`, `login(username, password)
      → AuthenticatedUser`. 예외: `UsernameTakenException`,
      `BadCredentialsException`.
    - `JwtService.java` — `issue(userId, username) → token`,
      `parse(token) → AuthPrincipal`. HS256, exp = `mirboard.jwt.expiresInSeconds`.
    - `AuthPrincipal.java` — `record AuthPrincipal(long userId, String username)`.
  - `com.mirboard.infra.rest.auth/`
    - `AuthController.java` — `/api/auth/register`, `/api/auth/login` 핸들러 +
      요청/응답 DTO.
    - `MeController.java` — `/api/me`.
  - `com.mirboard.infra.config/`
    - `SecurityConfig.java` — stateless `SecurityFilterChain`. `/api/auth/**` 와
      `/api/games` (다음 청크) 만 permitAll, 나머지는 authenticated.
    - `JwtAuthFilter.java` — `OncePerRequestFilter`, `Authorization: Bearer ...`
      파싱 → SecurityContext에 `AuthPrincipal` 셋팅.
  - `com.mirboard.infra.web/`
    - `GlobalExceptionHandler.java` — `@RestControllerAdvice`, 도메인 예외 →
      `docs/api.md` 의 `{error:{code,message,details}}` 포맷.
  - 테스트
    - `AuthServiceTest` — 정책 위반, 중복 username, 인증 성공/실패.
    - `JwtServiceTest` — 발급→파싱 round-trip, 만료 토큰, 잘못된 서명.
    - `AuthControllerIntegrationTest` — `@SpringBootTest` + Testcontainers MySQL.
      `/register` → `/login` → `/me` 시나리오.
- **검증**: 위 통합 테스트 통과 + `curl` 시나리오 README 에 추가.

#### Phase 2c — 게임 카탈로그 (GameRegistry + Tichu stub)  ✅ 완료
- **목표**: `GET /api/games` 가 티츄(AVAILABLE) 만 반환. Phase 3 에서 채울 엔진은
  스텁이지만 메타데이터는 진짜.
- **신규 파일**
  - `com.mirboard.domain.game.core/`
    - `GameDefinition.java` — `sealed interface` (현재는 `permits
      TichuGameDefinition`).
    - `GameStatus.java` — enum `AVAILABLE | COMING_SOON | DISABLED`.
    - `GameRegistry.java` — `@Component`, 생성자 주입 `List<GameDefinition>` →
      `Map<String, GameDefinition>`. `available()`, `catalog()`, `require(id)`.
    - `GameEngine.java`, `GameContext.java`, `GameAction.java`, `GameEvent.java`
      — Phase 3 에서 채울 sealed interface 빈 골격만.
  - `com.mirboard.domain.game.tichu/`
    - `TichuGameDefinition.java` — `@Component` (sealed permit), 메타값 하드코딩.
      `newEngine` 는 `UnsupportedOperationException` 로 마킹 ("Phase 3 에서 구현").
  - `com.mirboard.infra.rest.games/`
    - `GameCatalogController.java` — `/api/games`, `/api/games/{id}`.
  - 테스트
    - `GameRegistryTest` — 빈 수집/정렬/`require` 미존재 케이스.
    - `GameCatalogControllerTest` — `@WebMvcTest`, 카탈로그 응답 형식 검증.

#### Phase 2d — 방 (Room) + Redis 원자성  ✅ 완료
- **목표**: 4명 동시 입장 시 capacity 위반 0건. `/api/rooms` 전체 동작.
- **신규 파일**
  - `com.mirboard.domain.lobby.room/`
    - `RoomStatus.java` — enum WAITING/IN_GAME/FINISHED.
    - `Room.java` — record (메타 DTO).
    - `RoomService.java` — create/list/join/leave/get/resync(stub).
    - `RoomRepository.java` — Redis 접근 추상화. `RedisTemplate<String,String>` 위에
      Lua 스크립트 실행.
    - 리소스: `server/src/main/resources/lua/room_join.lua`,
      `room_leave.lua`. `DefaultRedisScript<String>` 로 로드.
  - `com.mirboard.infra.config/RedisConfig.java` — String/Hash serializer 명시.
  - `com.mirboard.infra.rest.rooms/RoomController.java` — `/api/rooms` 모음.
  - 테스트
    - `RoomServiceConcurrencyIT` — Testcontainers Redis, 10개 스레드가 한 방에 join
      시도해도 capacity(=4) 초과 0건.
    - `RoomControllerIntegrationTest` — 생성/입장/조회/퇴장 시나리오, JWT 필요.
- **수정 파일**: `SecurityConfig` — `/api/games`, `/api/rooms/**`, `/api/me`,
  `/api/me/stats` 등의 authenticated 룰 정리.

#### Phase 2e — WebSocket / STOMP + 로비 채팅  ✅ 완료
- **목표**: 로그인한 유저가 STOMP 로 CONNECT 하고 `/topic/lobby/chat`,
  `/topic/lobby/rooms` 를 구독/발행.
- **신규 파일**
  - `com.mirboard.infra.config/WebSocketConfig.java` — `@EnableWebSocketMessageBroker`,
    SimpleBroker(`/topic`, `/user/queue`), app prefix `/app`, user prefix `/user`,
    SockJS endpoint `/ws`.
  - `com.mirboard.infra.ws/StompAuthChannelInterceptor.java` — CONNECT 시 JWT 헤더
    검증, `Principal` 셋팅.
  - `com.mirboard.infra.ws.lobby/LobbyStompController.java` — `/app/lobby/chat` 핸들러.
  - `com.mirboard.infra.ws.rooms/RoomLobbyEventPublisher.java` — Room 변경 시
    `/topic/lobby/rooms` 브로드캐스트.
  - 테스트
    - `WebSocketAuthIntegrationTest` — JWT 없는 CONNECT 거절.
    - `LobbyChatIntegrationTest` — 두 클라이언트가 채팅 송수신.

### Phase 2 완료 (Done) 정의
1. `gradle :server:test` 전체 통과.
2. `gradle :server:bootRun` 후 위 모든 엔드포인트가 `curl` / Postman 으로 동작.
3. 4명 동시 입장 부하 테스트(`RoomServiceConcurrencyIT`)에서 capacity 위반 0건.
4. `docs/decisions.md` 에 Phase 2 진입/구현 결정 항목 추가.
5. README 의 명령 카탈로그에 `gradle :server:bootRun`, `gradle :server:test --tests
   ...` 예시 보강.

---

## Phase 3 — 티츄 코어 (룰 엔진)

### Phase 3 — 청크 분해 (review-able chunks)

| 청크 | 내용 | 상태 |
| --- | --- | --- |
| 3a | `card/` 패키지 — Suit/Special/Card record + Deck (SecureRandom + 시드 고정 테스트) | ✅ 완료 |
| 3b | `hand/` 패키지 — HandType enum, HandDetector(Phoenix 미고려), HandComparator | ✅ 완료 |
| 3c | Phoenix 와일드 + Mahjong 소원 + 특수카드 룰 (Wish, PlayContext) | ✅ 완료 |
| 3d | `state/` + `action/` — TichuState sealed, TichuAction sealed, TurnManager, ActionValidator | ✅ 완료 |
| 3e | `scoring/` — ScoreCalculator (트릭 점수, 1등 클리어, 더블 빅토리, 티츄 ±100/±200) | ✅ 완료 |
| 3f | TichuEngine 통합 + `TichuGameDefinition.newEngine` 실구현 | ✅ 완료 |

각 청크 끝에 변경 요약 + 다음 청크 진입 동의.



### 3.1 카드 모델
```java
enum Suit { JADE, SWORD, PAGODA, STAR }            // 일반 4슈트
enum Special { MAHJONG, DOG, PHOENIX, DRAGON }      // 4장의 특수카드
record Card(Suit suit, int rank /*2-14*/, Special special /* nullable */)
```
- 총 56장 = 일반 13×4 + 특수 4.
- `Deck.shuffled(SecureRandom)` 로 셔플.

### 3.2 분배
1. 각자 8장 → `HAND_DEALT` 큐로 전송 → Grand Tichu 선언 기회.
2. 각자 +6장 (총 14) → `HAND_DEALT_FULL` → Tichu 선언 기회.
3. 카드 패스: 좌/우/파트너에게 각 1장 → 동시 공개.

### 3.3 족보 판별 (`HandDetector`)
- 검출 종류: `SINGLE`, `PAIR`, `TRIPLE`, `FULL_HOUSE`, `STRAIGHT(≥5)`, `CONSECUTIVE_PAIRS(≥3쌍)`, `BOMB(four-of-a-kind)`, `STRAIGHT_FLUSH_BOMB(≥5, 동일 슈트)`.
- 비교 (`HandComparator`): 같은 타입·길이 안에서만 일반 비교. BOMB은 임의 타입을 끊을 수 있고, BOMB끼리는 길이 우선 → 랭크.
- Phoenix 와일드 처리: SINGLE 단독 시 현재 트릭 최강 +0.5, 콤보 안에서는 일반 카드 대체 (Bomb/Straight-Flush-Bomb 구성에는 사용 불가). 검출기가 가능한 해석을 모두 생성하고 가장 강한 합법 해석을 선택.

### 3.4 특수 카드 룰
- **Mahjong(1)**: 보유자가 첫 트릭 리드. 카드 낼 때 2~14 중 하나를 소원으로 지정 가능. 소원이 살아있는 동안 가능한 한 해당 랭크를 포함한 합법 플레이를 강제.
- **Dog**: 단독으로만 플레이. 즉시 파트너 차례로 이동. 점수 0.
- **Dragon**: 최강 싱글. 트릭을 가져가면 +25점. 본 트릭을 **상대 팀 둘 중 하나에게 양도** 필수 (`GIVE_DRAGON_TRICK`).
- **Phoenix**: 1/4 점수 카드 아니면 -25, 보유자에게 마이너스.

### 3.5 점수 계산 (`ScoreCalculator`)
- 카드 점수: 5(5점), 10(10점), K(10점), Dragon(+25), Phoenix(-25).
- 한 라운드 종료 조건: 한 팀의 두 명이 모두 카드를 모두 소진(=더블 빅토리, +200) 또는 마지막 한 명 남을 때까지.
- 1등 클리어(혼자 손패 0) → 본인 트릭 점수 + 상대팀 트릭 점수 모두 본인 팀, 남은 손패는 상대팀 다른 한 명에게.
- 티츄 ±100, 그랜드 티츄 ±200 (성공/실패).

### 3.6 액션 검증 (`ActionValidator`)
- 차례 검증, 보유 카드 검증, 족보 적법성, 소원 강제, Phoenix 사용 제한, Dragon 양도 대상 검증.
- 위반 시 본인 큐로 `ERROR` 발행, 상태 변경 없음.

### 3.7 단위 테스트 (필수)
- 각 족보 인식 (양성/음성 케이스 각 ≥3).
- 비교: 동일 타입 비교, BOMB 우선, BOMB vs BOMB, STRAIGHT_FLUSH_BOMB 최강.
- Phoenix 엣지: 페어/스트레이트/풀하우스 대체, 봉황+에이스 단독.
- 소원 강제 활성/해제 시 카드 강제 선택 검증.
- Dog 즉시 파트너 턴.
- Dragon 트릭 양도 검증.
- 점수: 단일 라운드, 더블 빅토리, 티츄 성공/실패, 그랜드 티츄 성공/실패.
- **목표 커버리지**: `domain.game.tichu` 의 hand/scoring 패키지 라인 ≥ 90%.

---

## Phase 4 — 통합 (WS + 클라이언트 + 재접속)

### Phase 4 — 청크 분해 (review-able chunks)

| 청크 | 내용 | 상태 |
| --- | --- | --- |
| 4a | 게임 lifecycle: `GameStartingEvent`, `TichuGameStateStore`, `TichuRoundStarter`, RoomService 트리거 + IT | ✅ 완료 |
| 4b | STOMP 게임 액션 핸들러 (`GameStompController`), 락 + 액션 디스패치, 공개/비공개 이벤트 발행 | ✅ 완료 |
| 4c | TableView/PrivateHand 매퍼 + `/api/rooms/{id}/resync` 실구현 | ✅ 완료 |
| 4d | 프론트엔드 스캐폴드: Vite + React + Auth + GameHub + Lobby 페이지 | ✅ 완료 |
| 4e | 프론트엔드 GameTable + `useStompRoom` 훅 + 카드 클릭 선택 (dnd-kit 보류) | ✅ 완료 |
| 4f | 재접속 동기화 + 1게임 시연 검증 (강제 종료 후 복원) | ✅ 완료 |

### 4.1 WebSocket 컨트롤러 1
```
@MessageMapping("/room/{roomId}/action")
public void onAction(@DestinationVariable String roomId,
                     @Payload ActionEnvelope env,
                     Principal principal) {
  // 1) 락 획득 (room:{id}:lock, 짧은 TTL)
  // 2) 현재 상태 로드 → ActionValidator → GameEngine.apply()
  // 3) 새 상태 저장 → 공개 이벤트는 /topic/room/{id}, 비공개는 /user/queue
  // 4) 락 해제
}
```
- 이벤트 시퀀스 번호(`seq`)는 `room:{id}:seq` INCR 로 단조 증가.

### 4.2 상태 은닉
- `TableView` (공개): 각자의 손패 **장수만**, 트릭 히스토리, 현재 턴, 점수, 티츄 선언 상태.
- `PrivateHand` (본인만): 실제 카드 배열, 가능한 합법 플레이 힌트(선택).

### 4.3 React 훅 `useStompRoom(roomId)`
- CONNECT 시 JWT 헤더 전달.
- `/topic/room/{roomId}` + `/user/queue/room/{roomId}` 동시 구독.
- 자동 재접속(지수 백오프) + 재접속 직후 `GET /api/rooms/{id}/resync` 호출하여 상태 복원.
- 수신 이벤트는 Zustand 스토어에 reducer 패턴으로 반영.

### 4.4 재접속 동기화
- `/api/rooms/{id}/resync`: 인증된 사용자가 해당 방의 참여자임을 확인 → `room:{id}:state` + `room:{id}:hand:{userId}` 반환.
- 클라는 자체 `seq` 보다 큰 이벤트만 적용 (idempotent).

### 4.5 UI 흐름
- Login → Lobby (방 목록 + 생성 + 채팅) → Room (4/4 모이면 자동 시작) → GameTable.
- GameTable: 본인 손패 정렬·드래그(`@dnd-kit`), 선택 셋 → 클라에서 족보 후보 표시(UX 힌트만, 권한은 서버) → "Play" 버튼.
- 특수 인터랙션 모달: 소원 선택, 드래곤 양도 대상 선택, 카드 패스 단계.

---

## 검증 (단계별 Done 정의)

| Phase | Done 기준 |
| --- | --- |
| 1 | `docs/*.md` 3종 사용자 승인. `docker-compose up` 으로 mysql/redis 기동 확인. Flyway V1 적용 성공. |
| 2 | 회원가입/로그인 e2e 통과, 방 4인 동시 입장 동시성 테스트(capacity 위반 0건), 통합 대기실 채팅 양방향. |
| 3 | 룰 단위 테스트 ≥ 90% (hand/scoring), 모든 특수카드 시나리오 통과. |
| 4 | 4명 한 게임 전체 플레이 성공, 임의 1명 강제 종료 후 5초 내 재접속하면 UI 상태 완전 복원, 잘못된 액션 시 본인만 ERROR 수신·다른 플레이어 상태 불변. |

---

## 작업 순서 (사용자 승인 게이트)
1. **Phase 1 설계 산출물** → 사용자 검토 → 승인 후 Phase 2. ✅
2. **Phase 2 로비 모듈 + 통합테스트** → 사용자 검토 → 승인 후 Phase 3. ✅ (2a/2b/2c/2d/2e 모두 완료, 사용자 검증 대기)
3. **Phase 3 티츄 코어 + 단위테스트 리포트** → 사용자 검토 → 승인 후 Phase 4. ✅ (3a~3f 모두 완료, 사용자 검증 대기)
4. **Phase 4 통합 + 시연** → 최종 검증. ✅ (4a~4f 모두 완료, README 시연 플로우 정리)

---

## Phase 5 — Post-MVP Follow-up

| 청크 | 내용 | 상태 |
| --- | --- | --- |
| 5a | 매치 결과 영속화 + win/lose 카운트 + 방 FINISHED 전이 | ✅ 완료 |
| 5b | Dealing/Passing 프리뤼드 (Grand Tichu, Tichu, 카드 패스) | ✅ 완료 |
| 5c | 멀티 라운드 누적 점수 + 1000점 매치 종료 | ✅ 완료 |
| 5d | 라이브 patch (seq 기반 부분 갱신, /resync polling 축소) | ✅ 완료 |
| 5e | UI polish + @dnd-kit 손패 정렬 + i18n 베이스 | ✅ 완료 |

각 Phase 종료 시 변경사항 요약 + 다음 Phase 진입 동의를 사용자에게 요청.

---

## Phase 6 — Post-MVP 확장 (E → A → C → D)

사용자 결정 (D-33): MVP 룰 UX 마감(E) → 운영성/관전(A) → 디자인 토큰/공용 컴포넌트(C) →
멀티 인스턴스(D) 순. **Option B (새 게임 추가) 는 본 사이클에서 제외** — 별도 사이클.
외부 브로커 채택: Redis Pub/Sub.

### Phase 6E — 티츄 룰 UX 마감

| 청크 | 내용 | 상태 |
| --- | --- | --- |
| 6E-1 | `MakeWishModal` — Mahjong 카드 플레이 시 rank 2~14 선택 | ✅ 완료 (`bacaf34`) |
| 6E-2 | `GiveDragonTrickModal` — Dragon 트릭 양도 좌석 선택 | ✅ 완료 (`3642655`) |
| 6E-3 | Phoenix 단독 SINGLE 표시 — currentTop 영역 배지 + 툴팁 | ✅ 완료 (`f31bb26`) |
| 6E-4 | 이벤트 리듀서 회귀 테스트 — TRICK_TAKEN 후 activeWishRank 유지 / DRAGON_GIVEN seq-only | ✅ 완료 |

**Done 기준**: 4탭 모의 게임에서 Mahjong 소원/Dragon 양도/Phoenix 단독 SINGLE 전부
정상 UX. `npm --prefix client run test` 그린.

### Phase 6A — 운영 강화 + 관전 모드

| 청크 | 내용 | 상태 |
| --- | --- | --- |
| 6A-1 | MDC (userId/roomId/eventId) + logback-spring.xml 표준화 | 대기 |
| 6A-2 | 도메인 이벤트 감사 로그 (RoomService/TichuRoundStarter/GameStompController/MatchResultRecorder) | 대기 |
| 6A-3 | Actuator + Micrometer + Prometheus endpoint | 대기 |
| 6A-4 | 도메인 카운터 (방/게임/라운드/매치/액션 reject) | 대기 |
| 6A-5 | 관전 모드 — Room.spectatorIds, `/api/rooms/{id}/spectate`, resync 검증 확장 | 대기 |
| 6A-6 | 클라 관전 진입 — 로비 IN_GAME 방 "구경하기" + GameTable spectator mode | 대기 |

**Done 기준**: `/actuator/prometheus` 에 도메인 카운터 노출. 관전자 통합 테스트 (TableView O,
PrivateHand X, 액션 송신 거절). 로그 MDC 일관성 확인.

### Phase 6C — UI 디자인 토큰 + 공용 컴포넌트

| 청크 | 내용 | 상태 |
| --- | --- | --- |
| 6C-1 | CSS 디자인 토큰 (`tokens.css`) + hardcode 색상 치환 | 대기 |
| 6C-2 | 공용 컴포넌트 디렉토리 (`Button`, `Modal`, `Input`, `Card`, `Stack`, `Badge`) | 대기 |
| 6C-3 | 페이지 리팩토링 (Login/GameHub/Lobby/Room) | 대기 |
| 6C-4 | 모바일 점검 (480px/720px) + media query 정리 | 대기 |

**Done 기준**: `styles.css` 의 hardcode 색상 0건. `npm run build` 통과. Chrome 모바일
시뮬레이터 화면 깨짐 없음.

### Phase 6D — 멀티 인스턴스 (Redis Pub/Sub)

| 청크 | 내용 | 상태 |
| --- | --- | --- |
| 6D-1 | `MessageGateway` 추상 + `RedisMessageGateway` / `InMemoryMessageGateway` 구현체 | 대기 |
| 6D-2 | STOMP 브로드캐스트 다중인스턴스화 — gateway publish + 인스턴스별 relay | 대기 |
| 6D-3 | ApplicationEvent → Redis Pub/Sub `DomainEventBus` + eventId 기반 dedup | 대기 |
| 6D-4 | docker-compose `multi` 프로파일 + README 분산 시연 가이드 | 대기 |

**Done 기준**: 기존 통합 테스트 그린 + 두 인스턴스 분산 시연 시 양쪽 동기화. eventId 중복
처리 0건.

각 Phase 종료 시 변경사항 요약 + 다음 Phase 진입 동의를 사용자에게 요청.
