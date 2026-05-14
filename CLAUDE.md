# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 현황

**Mirboard** — 웹 기반 턴제 보드게임 플랫폼. 공통 허브/로비 + 1차 게임으로 티츄(Tichu).

현재는 **Phase 1 (설계) 단계 완료** 상태. 작성된 산출물은:
- `docs/api.md` — REST 명세
- `docs/stomp-protocol.md` — WebSocket/STOMP envelope, 토픽·큐 카탈로그
- `docs/redis-keys.md` — Redis 키 타입/TTL/Lua 원자성 전략
- `server/src/main/resources/db/migration/V1__init.sql` — Flyway 초기 스키마

**아직 작성되지 않은 것**: Gradle 빌드 파일, Java 소스, 클라이언트 프로젝트. Phase 2부터 추가됨. 새 코드를 쓸 때는 위 docs를 계약(contract)으로 취급.

## Phase Gate 작업 규칙

작업은 4단계로 진행되며 **각 Phase 완료 시 반드시 사용자 검토/승인을 받은 후 다음 Phase로 진입한다.** Phase를 임의로 건너뛰지 말 것.

1. **Phase 1**: DB/API/프로토콜 설계 (완료)
2. **Phase 2**: 로비 모듈 (회원가입, JWT, 방 생성/입장, 동시성)
3. **Phase 3**: 티츄 룰 엔진 + 단위 테스트 ≥ 90%
4. **Phase 4**: WebSocket 통합 + React 클라이언트 + 재접속 동기화

상세는 플랜 파일 참조.

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
- 현재 허용 컬럼: `id`, `username`, `password_hash`, `win_count`, `lose_count`, `created_at`.
- 로그인/회원가입 엔드포인트도 헤더/쿠키 식별자를 기록하지 않는다 (IP는 인프라 레벨만).

### 도메인 경계 (Modular Monolith)
- `domain.lobby` → `domain.game.tichu` 직접 의존 **금지**.
- 게임 디스패치는 반드시 `domain.game.core.GameRegistry` 를 거친다 (`GameDefinition` Bean 자동 수집).
- `domain.game.tichu` 는 `domain.game.core` 인터페이스만 의존.
- `infra/ws/*`, `infra/rest/*` 컨트롤러는 도메인 서비스를 호출만 한다. **룰 로직 금지**.
- 새 게임 추가 절차: `domain.game.{newgame}` 패키지 + `GameDefinition @Component` Bean 등록 → 카탈로그/방 생성/엔진 디스패치가 자동 연결됨. 로비/허브 컨트롤러 수정 불필요.

## 기술 스택 결정사항

| 영역        | 결정                                                                                         |
|-----------|--------------------------------------------------------------------------------------------|
| JDK       | **Java 25 (LTS)** — Virtual Threads 기본 활성화 (`spring.threads.virtual.enabled=true`)         |
| Backend   | **Spring Boot 4.0.1** (Jakarta EE 11 / Spring Framework 7). `javax.*` 금지, `jakarta.*` 만 사용 |
| Build     | Gradle 8.10+, Kotlin DSL                                                                   |
| Auth      | JWT HS256 12h, BCrypt. 시크릿은 `MIRBOARD_JWT_SECRET` 환경변수                                     |
| Migration | **Flyway** — JPA `ddl-auto` 사용 금지                                                          |
| Frontend  | Vite + React 18 + TypeScript, `@stomp/stompjs` + SockJS, `@dnd-kit`, Zustand + React Query |
| Data      | MySQL 8 (영속), Redis 7 (실시간 세션/방 상태)                                                        |
| Test      | JUnit 5 + Mockito + Testcontainers / Vitest + RTL                                          |

### Java 25 / Spring Boot 4.0 활용 패턴
- `HandType`, `GameAction`, `GameEvent` 는 **sealed interface** 로 정의 → `switch` 표현식 패턴 매칭으로 누락 케이스를 컴파일러가 강제 검출.
- 상태 객체 (`TichuState`, `TrickState`, `Card`) 는 **record** + `with*` 메서드로 불변 전이.
- WebSocket/STOMP 핸들러는 가상 스레드 위에서 실행되어 동시 게임 수 증가 시에도 스레드 풀 고갈 없음.

## 사용자 플로우

```
[로그인] → [Game Hub] → [Game Lobby] → [Room] → [Game Table]
            └─ GET /api/games           └─ GET /api/rooms?gameType=TICHU
```

- 통합 대기실 채팅 (`/topic/lobby/chat`) 은 Hub/Lobby 어느 화면에서든 동일하게 보이는 플랫폼 전역 채팅.
- 게임별 격리 채팅은 MVP 범위 밖.

## 자주 쓰는 명령

### 인프라 (현재 사용 가능)
```bash
# MySQL + Redis 기동
docker compose up -d mysql redis

# Flyway 마이그레이션 적용 (스키마 변경 시마다)
docker compose --profile migrate run --rm flyway

# 스키마 확인
docker compose exec mysql mysql -umirboard -pmirboardpw mirboard -e "SHOW TABLES;"
```

기본 자격증명 (개발 한정): MySQL `mirboard / mirboardpw`, DB `mirboard`, Redis 비밀번호 없음.

### 서버 (Phase 2a~ 사용 가능)
처음 한 번만:
```bash
gradle wrapper --gradle-version 8.10.2   # 또는 docker run gradle:8.10.2-jdk21 ...
```
이후:
```bash
./gradlew :server:bootRun
./gradlew :server:test
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
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.DealingLifecycleTest"
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.persistence.TichuMatchStateTest"
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.lifecycle.TichuRoundStarterIT"
./gradlew :server:test --tests "com.mirboard.infra.ws.GameStompControllerIntegrationTest"
./gradlew :server:test --tests "com.mirboard.infra.rest.rooms.RoomResyncIntegrationTest"
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.persistence.MatchResultRecorderIT"
```
통합 테스트는 Docker 가 떠 있어야 함 (Testcontainers 가 MySQL 8 컨테이너를 띄움).

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

- 서버 → 클라 공개: `/topic/room/{roomId}` (이벤트: `ROOM_UPDATED`, `GAME_STARTED`, `PLAYED`, `TURN_CHANGED`, `TRICK_TAKEN`, `ROUND_ENDED`, `GAME_ENDED` 등)
- 서버 → 클라 비공개: `/user/queue/room/{roomId}` (이벤트: `HAND_DEALT`, `HAND_DEALT_FULL`, `RESYNC`, `ERROR`)
- 클라 → 서버: `/app/room/{roomId}/action` (액션: `DECLARE_GRAND_TICHU`, `DECLARE_TICHU`, `PASS_CARDS`, `PLAY_CARD`, `PASS`, `MAKE_WISH`, `GIVE_DRAGON_TRICK`)

전체 카탈로그: `docs/stomp-protocol.md`.

## Redis 키 / 원자성

- `room:{id}` (HASH), `room:{id}:players` (LIST), `room:{id}:state` (JSON), `room:{id}:hand:{userId}` (JSON, 서버만), `room:{id}:seq` (INCR), `room:{id}:lock` (SET NX EX 2s).
- 방 입장/퇴장은 **Lua 스크립트로 원자화** — capacity 위반 0건 보장이 Phase 2 검증 기준.
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
- Windows 환경 (`C:\workspace\mirboard\`). 셸은 bash. 파일 경로는 forward slash 권장.
- Phase 1 산출물(`docs/*.md`, `V1__init.sql`)을 변경하려면 그 자체가 설계 변경. 코드보다 먼저 docs를 고치고 사용자 승인을 받을 것.
