# Mirboard

웹 기반 턴제 **보드게임 플랫폼**. 여러 보드게임을 호스팅하는 허브를 갖되, 첫 출시
게임으로 **티츄(Tichu)** 를 구현한다.

## 사용자 플로우

```
[로그인]  →  [Game Hub: 게임 선택]  →  [Lobby: 방 목록/생성]  →  [방]  →  [게임]
              └─ /api/games            └─ /api/rooms?gameType=TICHU
```

- **Game Hub**: 플레이 가능한 게임 카탈로그를 보여주는 화면. 서버의
  `GameRegistry` 가 진실 공급원. 미구현 게임은 `COMING_SOON` 으로 비활성화 표시.
- **Game Lobby**: 선택된 게임 타입으로 필터링된 방 목록과 통합 채팅.
- **새 게임 추가**: `domain.game.{newgame}` 패키지 신설 + `GameDefinition` Bean
  등록만으로 카탈로그/방 생성/룰 디스패치가 자동 연결. 로비/허브 코드 수정 불필요.

## 아키텍처 요약

- **Modular Monolith** — 단일 Spring Boot 서버, 도메인 패키지 경계로 분리.
- **Server-Authoritative** — 셔플/분배/족보판별/점수계산은 전부 서버. 클라이언트는
  입력기 + 뷰어 역할.
- **State Hiding** — 본인 손패는 본인 STOMP 큐로만, 공개 정보는 토픽으로.
- **개인정보 최소** — `users` 테이블은 `username`, `password_hash`, 전적만 저장.

## 런타임 요구사항

| 구성 요소 | 버전 |
| --- | --- |
| JDK | **Java 25 (LTS)** |
| Build | Gradle 8.10+ (Kotlin DSL) |
| Backend | Spring Boot **4.0.1** (Jakarta EE 11 / Spring Framework 7) |
| Frontend | Node 20+ , Vite + React 18 + TypeScript |
| Infra | MySQL 8.0, Redis 7 (docker-compose 제공) |

> Virtual Threads 기본 활성화(`spring.threads.virtual.enabled=true`) 및
> sealed/pattern-switch 적극 사용. 모든 import는 `jakarta.*` (javax 금지).

자세한 Phase별 계획은 [`docs/plans/mvp-roadmap.md`](docs/plans/mvp-roadmap.md).
주요 설계 결정/번복은 [`docs/decisions.md`](docs/decisions.md) 에 "제목 한 줄 +
짧은 문단" 형식으로 기록한다. 미래 작업자(사람/AI)가 본인 작업 전에 먼저 훑어볼 곳.

## 디렉토리

```
mirboard/
├── docker-compose.yml          # MySQL 8, Redis 7, (선택) Flyway
├── docs/                       # 설계 명세 (Phase 1 산출물) + 이력 + 플랜
│   ├── api.md                  # REST 명세
│   ├── stomp-protocol.md       # WebSocket/STOMP envelope & 이벤트
│   ├── redis-keys.md           # Redis 키/TTL/Lua 원자성
│   ├── decisions.md            # 설계 결정 이력 (한 줄 제목 + 짧은 문단)
│   └── plans/
│       └── mvp-roadmap.md      # Phase별 상세 계획 (canonical)
├── server/                     # Spring Boot 백엔드 (Phase 2~ 에서 구현)
│   └── src/main/resources/db/migration/V1__init.sql
│   # 패키지:
│   #   domain.lobby.*           (회원/방/채팅 — 게임 도메인 미의존)
│   #   domain.game.core.*       (GameDefinition, GameRegistry, GameEngine 인터페이스)
│   #   domain.game.tichu.*      (TichuGameDefinition + 룰 엔진)
└── client/                     # React 프론트엔드 (Phase 4 에서 구현)
    # 페이지: Login → GameHub → Lobby → Room → GameTable
```

## 로컬 의존성 기동

```bash
# MySQL + Redis 만 띄움
docker compose up -d mysql redis

# 처음 한 번 Flyway 마이그레이션 적용
docker compose --profile migrate run --rm flyway
```

기본 자격증명(개발 한정):

| 항목 | 값 |
| --- | --- |
| MySQL host | `127.0.0.1:3306` |
| MySQL database | `mirboard` |
| MySQL user / pw | `mirboard` / `mirboardpw` |
| Redis | `127.0.0.1:6379` |

운영 자격증명은 `.env` 파일 또는 배포 시크릿으로만 주입. JWT 서명 키는
`MIRBOARD_JWT_SECRET` 환경변수로 전달한다.

## 서버 빌드 / 실행 (Phase 2a)

처음 한 번 Gradle Wrapper 를 부트스트랩 (jar 가 바이너리라 리포에 포함되지 않음):

```bash
# 옵션 A — 로컬에 Gradle 8.10+ 가 설치되어 있는 경우
gradle wrapper --gradle-version 8.10.2

# 옵션 B — Docker 만으로 부트스트랩
docker run --rm -v "$PWD":/work -w /work gradle:8.10.2-jdk21 \
  gradle wrapper --gradle-version 8.10.2
```

JDK 25 가 로컬에 없으면 Gradle 의 foojay 리졸버가 자동으로 받아온다 (인터넷 필요).

서버 기동 / 테스트:

```bash
# 의존 컨테이너가 떠 있어야 함
docker compose up -d mysql redis

# 서버 기동
./gradlew :server:bootRun

# 단위 + 통합 테스트
./gradlew :server:test

# 특정 테스트만
./gradlew :server:test --tests "com.mirboard.domain.lobby.auth.AuthServiceTest"
```

**Phase 2a 동작 확인 포인트**
- 로그에 `Started MirboardApplication` 와 Flyway `Successfully applied N migration(s)` 출력.
- MySQL 에 `users`, `tichu_match_results`, `tichu_match_participants`,
  `flyway_schema_history` 테이블 존재.
- 8080 포트 listening (Spring Security 기본값에 의해 모든 요청 401 — 정상).

## Phase 2b — 인증 (Auth) 동작 확인

회원가입 → 로그인 → 본인 정보 조회:

```bash
# 1) 회원가입
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice_01","password":"validpass1"}'
# → {"userId":1,"username":"alice_01"}

# 2) 로그인 (JWT 획득)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice_01","password":"validpass1"}' | jq -r .accessToken)

# 3) 본인 정보 조회
curl -s http://localhost:8080/api/me -H "Authorization: Bearer $TOKEN"
# → {"userId":1,"username":"alice_01","winCount":0,"loseCount":0}
```

예외 케이스:
- 같은 username 재등록 → `409 USERNAME_TAKEN`
- 비밀번호 오류 → `401 BAD_CREDENTIALS`
- 토큰 없는 `/api/me` → `401 UNAUTHORIZED`
- username 규칙 위반 (`^[A-Za-z0-9_]{3,20}$`) → `400 INVALID_INPUT`

## Phase 2c — 게임 카탈로그

```bash
# 카탈로그 조회 (인증 필요)
curl -s http://localhost:8080/api/games -H "Authorization: Bearer $TOKEN"
# → {"games":[{"id":"TICHU","displayName":"티츄",
#              "shortDescription":"4인 파트너 카드 게임. 56장 덱과 4장의 특수 카드...",
#              "minPlayers":4,"maxPlayers":4,"status":"AVAILABLE"}]}

# 단일 게임
curl -s http://localhost:8080/api/games/TICHU -H "Authorization: Bearer $TOKEN"

# 미등록 게임 → 404 GAME_NOT_AVAILABLE
curl -s http://localhost:8080/api/games/UNKNOWN -H "Authorization: Bearer $TOKEN"
```

새 게임 추가 절차: `domain.game.{newgame}` 패키지에 `GameDefinition` 구현체를
`@Component` 로 만들면 카탈로그/단일조회 자동 노출. 로비/허브/REST 코드 수정 불필요.

## Phase 2d — 방(Room)

```bash
# 방 생성 (자동으로 본인이 host로 입장)
ROOM=$(curl -s -X POST http://localhost:8080/api/rooms \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"친구들 한 판","gameType":"TICHU"}' | jq -r .roomId)

# 방 목록 (WAITING 만, gameType 필터 가능)
curl -s "http://localhost:8080/api/rooms?gameType=TICHU" \
  -H "Authorization: Bearer $TOKEN"

# 입장 / 단일 조회 / 퇴장
curl -s -X POST http://localhost:8080/api/rooms/$ROOM/join  -H "Authorization: Bearer $TOKEN"
curl -s        http://localhost:8080/api/rooms/$ROOM        -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost:8080/api/rooms/$ROOM/leave -H "Authorization: Bearer $TOKEN"
```

예외 케이스:
- 방 만석 입장 → `409 ROOM_FULL`
- 이미 입장한 방 재입장 → `409 ALREADY_IN_ROOM`
- IN_GAME 으로 전환된 방 입장 → `409 GAME_ALREADY_STARTED`
- 등록 안 된 gameType → `404 GAME_NOT_AVAILABLE`
- 모르는 roomId → `404 ROOM_NOT_FOUND`

**원자성 보증**: `room_create.lua` / `room_join.lua` / `room_leave.lua` 가 Redis
단일 스레드 위에서 capacity 체크 → players push → 메타 갱신을 한 번에 처리.
`RoomServiceConcurrencyIT` 가 9 스레드 동시 입장으로 capacity=4 위반 0건을 검증.

## Phase 2e — WebSocket / STOMP

- 엔드포인트: `ws://<host>/ws` (raw) + SockJS fallback.
- CONNECT 시 `Authorization: Bearer <JWT>` 헤더 필수 — 없거나 위조면 거절.
- 채널:
  - `/topic/lobby/chat` — 로비 채팅 (서버 발행)
  - `/topic/lobby/rooms` — 방 변경 알림 (`ROOM_UPDATED` / `ROOM_DESTROYED`)
  - 클라 발행: `/app/lobby/chat` `{ "message": "..." }`
- 메시지 envelope: `{ "eventId", "type", "ts", "payload" }`. 상세는
  [`docs/stomp-protocol.md`](docs/stomp-protocol.md).

브라우저에서 빠른 검증 (`@stomp/stompjs` 기준):
```js
import { Client } from '@stomp/stompjs';
const client = new Client({
  brokerURL: 'ws://localhost:8080/ws',
  connectHeaders: { Authorization: `Bearer ${token}` },
  onConnect: () => {
    client.subscribe('/topic/lobby/chat', (m) => console.log(JSON.parse(m.body)));
    client.publish({ destination: '/app/lobby/chat', body: JSON.stringify({ message: 'hi' }) });
  },
});
client.activate();
```

## 작업 흐름 (Phase Gate)

1. **Phase 1 — 설계**: 본 문서 + `docs/*.md` + Flyway V1.
2. **Phase 2 — 로비 모듈**: 회원가입/로그인/방 입장.
3. **Phase 3 — 티츄 룰 엔진** (+ 단위 테스트 ≥ 90%).
4. **Phase 4 — 실시간 통합 + 재접속 동기화**.

각 Phase 종료 시 사용자 검토/승인 후 다음 Phase로 진입.
