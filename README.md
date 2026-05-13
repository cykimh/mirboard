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

자세한 Phase별 계획은 `%USERPROFILE%\.claude\plans\quiet-strolling-platypus.md`.
주요 설계 결정/번복은 [`docs/decisions.md`](docs/decisions.md) 에 ADR-lite 형식으로
기록한다. 미래 작업자(사람/AI)가 본인 작업 전에 먼저 훑어볼 곳.

## 디렉토리

```
mirboard/
├── docker-compose.yml          # MySQL 8, Redis 7, (선택) Flyway
├── docs/                       # 설계 명세 (Phase 1 산출물) + 이력
│   ├── api.md                  # REST 명세
│   ├── stomp-protocol.md       # WebSocket/STOMP envelope & 이벤트
│   ├── redis-keys.md           # Redis 키/TTL/Lua 원자성
│   └── decisions.md            # 설계 결정 이력 (ADR-lite)
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

## 작업 흐름 (Phase Gate)

1. **Phase 1 — 설계**: 본 문서 + `docs/*.md` + Flyway V1.
2. **Phase 2 — 로비 모듈**: 회원가입/로그인/방 입장.
3. **Phase 3 — 티츄 룰 엔진** (+ 단위 테스트 ≥ 90%).
4. **Phase 4 — 실시간 통합 + 재접속 동기화**.

각 Phase 종료 시 사용자 검토/승인 후 다음 Phase로 진입.
