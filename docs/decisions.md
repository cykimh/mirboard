# 설계 결정 이력

각 결정은 **제목 한 줄 + 짧은 문단(2~4문장)**. 시간순(위→아래)으로 추가한다.
번복/폐기 시 항목을 지우지 말고 끝에 `*폐기 → D-XX*` 또는 `*변경 → D-XX*` 한 줄을
덧붙인다. 코드 작업 전에 항목을 추가하고, 영향받는 `docs/*.md` / `CLAUDE.md` /
`README.md` / `docs/plans/*.md` 와의 정합성을 사용자 승인 단계에서 검토한다.

---

## D-01 (2026-05-13) — Modular Monolith + Server-Authoritative + State Hiding

단일 Spring Boot 서버 안에서 도메인 패키지로 경계를 나누고, 셔플·족보·점수 같은 룰
연산은 전부 서버에서 처리한다. 본인 손패는 STOMP 사용자 큐로만 보낸다. MVP에
마이크로서비스는 과잉이고, 클라이언트 신뢰 시 치팅 위험이 있기 때문.

## D-02 (2026-05-13) — 사용자 식별 정보 최소화 (스키마 레벨)

`users` 테이블은 `id, username, password_hash, win_count, lose_count, created_at`
만 둔다. `email, phone, real_name, birth_date, address` 컬럼 추가 금지. MVP에서
이메일 검증·SMS 인증이 불필요하고 노출 면적을 줄여야 한다.

## D-03 (2026-05-13) — Redis Lua 스크립트로 방 입장 원자화

방의 capacity 체크 → players 리스트 push → 메타 갱신을 단일 Lua 스크립트로 묶는다.
4명이 동시에 입장해도 capacity 위반 0건을 보장. 분산 락 없이 단일 인스턴스 Redis의
단일 스레드 원자성으로 충분하다.

## D-04 (2026-05-13) — Phase Gate 작업 흐름

Phase 1(설계) → 2(로비) → 3(룰 엔진) → 4(통합) 순서로 진행하고, 각 Phase 종료 시
사용자 검토/승인을 거친다. 초기에 한꺼번에 코드를 짜면 재작업 비용이 커서 단계
계약을 먼저 확정한 뒤 다음 단계로 넘어가는 게 안전하다.

## D-05 (2026-05-13) — 백엔드: Java 25 + Spring Boot 4.0.1

사용자 요청으로 LTS Java 25 + Spring Boot 4.0.1 (Jakarta EE 11 / Spring Framework
7) 채택. sealed types · 가상 스레드 · record + pattern switch 가 표준 코드 스타일이
된다. 모든 import는 `jakarta.*`, `javax.*` 금지.
*변경*: 초기에 Java 17 / Spring Boot 3.x 로 잡았던 것을 이 결정으로 대체.

## D-06 (2026-05-13) — 단일 게임 가정 폐기 → 플랫폼 (Game Hub)

로그인 직후 Game Hub 에서 게임을 선택하고 해당 게임 전용 Lobby로 진입한다.
카탈로그의 진실 공급원은 `GameRegistry`. 새 게임은 `domain.game.{newgame}` 패키지 +
`GameDefinition` Bean 등록만으로 자동 노출 — 로비/허브 코드는 손대지 않는다.

## D-07 (2026-05-13) — 설계 이력 로그 도입

`docs/decisions.md` 와 `CLAUDE.md` 의 워크플로우 절을 도입해서 결정/번복을 추적한다.
코드/문서에 흩어져 있던 "왜 이렇게 했는가" 가 휘발되지 않도록 한 곳에 모은다.

## D-08 (2026-05-13) — Phase 2 시작: Gradle/SB 스캐폴드

빈 Spring Boot 가 MySQL/Flyway/Redis 와 통신하는 빌드 환경부터 확정한다. 도메인
로직 0 — 빌드/의존성이 검증되지 않은 채 코드를 쌓으면 디버깅 표면이 커진다.
`gradle-wrapper.jar` 만 바이너리라 사용자가 한 번 `gradle wrapper` 부트스트랩 필요.

## D-09 (2026-05-13) — Phase 2b: Auth 모듈 구현

회원가입/로그인/본인조회 + JWT 인증을 구현. 도메인 예외는 `AuthException` sealed
계층(4종)으로 모델링하고, `GlobalExceptionHandler` 가 `{error:{code,message,details}}`
포맷으로 변환한다. 토큰 만료/위조도 외부에는 `BAD_CREDENTIALS` 또는 `UNAUTHORIZED`
한 종류로만 보여 정보 누출을 줄였다.

## D-10 (2026-05-13) — 플랜은 프로젝트 내부에서 관리 + 결정 로그 포맷 단순화

플랜의 canonical 위치를 `docs/plans/mvp-roadmap.md` 로 옮긴다. Plan Mode 가
자동 생성하는 `%USERPROFILE%\.claude\plans\*.md` 는 임시 스크래치 패드 — ExitPlanMode
직후 변경분을 `docs/plans/` 로 동기화한다. 결정 로그 포맷도 4-필드 ADR에서 "제목 한
줄 + 짧은 문단" 으로 단순화 — 단발성 변경 추적에는 무거웠다.

## D-16 (2026-05-13) — Phase 3c: Phoenix 와일드 + Wish + PlayContext

`Hand` 에 `phoenixSingle` boolean 필드 추가 (Phoenix 단독 SINGLE 플레이 식별).
`HandDetector` 가 Phoenix 포함 카드면 rank 2~14 의 대체 카드를 하나씩 시도하여
가장 강한 비-BOMB 해석을 선택한다 (BOMB / SFB 는 Phoenix 사용 금지). `HandComparator`
는 challenger.phoenixSingle 일 때 Dragon 만 못 이기고 다른 SINGLE 은 무조건 이기는
규칙으로 분기. `Wish` (Mahjong 소원, rank 2~14, 활성/해제) 와 `PlayContext`
(현재 트릭 상위 + 활성 소원) 를 도메인 record 로 정의해 Phase 3d 의 액션 검증에서
사용. Phoenix 가 Mahjong(rank 1) 자리를 대체하는 것은 룰상 금지이므로 substitution
범위는 2~14 로 고정.

## D-15 (2026-05-13) — Phase 3b: 족보 판별 + 비교

`HandType` enum 8종 (SINGLE/PAIR/TRIPLE/FULL_HOUSE/STRAIGHT/CONSECUTIVE_PAIRS/
BOMB/STRAIGHT_FLUSH_BOMB), `Hand` record `(type, cards, rank, length)`. 검출 우선
순위는 STRAIGHT_FLUSH_BOMB → BOMB → FULL_HOUSE → CONSECUTIVE_PAIRS → STRAIGHT →
TRIPLE → PAIR (가장 강한 합법 해석을 선택). Phoenix 가 포함되면 본 청크에서는 빈
Optional (Phase 3c 에서 와일드 처리). 특수카드 제약: Dog/Dragon 은 SINGLE 만,
Mahjong 은 SINGLE 또는 STRAIGHT 만 (rank=1). 비교는 `HandComparator.canBeat`
하나로 — SFB 가 최강, 일반 BOMB 이 비-BOMB을 끊고, 같은 타입·길이 안에서만 일반
비교, 그 외는 false.

## D-14 (2026-05-13) — Phase 3a: 카드 / 덱 모델

`Card` 는 단일 record `(Suit, int rank, Special)` 로 일반/특수카드 모두 표현 (sealed
이중 타입은 패턴 매칭 이득 대비 직렬화·생성 복잡도가 커서 단일 record 채택).
정적 팩토리 `Card.normal/mahjong/dog/phoenix/dragon` 으로 생성 의도를 명확히 한다.
`Deck` 은 불변 — `Deck.shuffled(SecureRandom)` 이 운영 기본, `Deck.shuffled(Random)`
은 시드 고정 테스트용. 56장 = 일반 13×4 + 특수 4, 점수 (5=5/10=10/K=10/Dragon=+25/
Phoenix=-25) 는 `Card.points()` 에서 계산.

## D-13 (2026-05-13) — Phase 2e: WebSocket/STOMP + 로비 채팅

`@EnableWebSocketMessageBroker` 로 SimpleBroker(`/topic`, `/user/queue`) 활성,
endpoint `/ws` (SockJS fallback 포함). `StompAuthChannelInterceptor` 가 CONNECT
프레임의 `Authorization: Bearer ...` 를 검증해서 `accessor.setUser(AuthPrincipal)`.
없거나 위조면 `MessageDeliveryException` 으로 CONNECT 거절. `LobbyStompController`
는 `/app/lobby/chat` 으로 수신 → `/topic/lobby/chat` 으로 envelope(`{eventId,type,ts,
payload}`) 브로드캐스트. 방 변경은 `RoomService` 에서 `ApplicationEventPublisher`
로 `RoomChangedEvent` 를 발행하고, `RoomLobbyEventPublisher` 가 listener 로 `/topic/
lobby/rooms` 에 변환 — 도메인이 STOMP 를 모르도록 한 레이어 분리.

## D-12 (2026-05-13) — Phase 2d: Room + Redis Lua 원자성

`domain.lobby.room` 에 RoomStatus enum, Room record DTO, 도메인 예외 5종
(`RoomNotFound`, `RoomFull`, `AlreadyInRoom`, `NotInRoom`, `GameAlreadyStarted`),
`RoomRepository` (StringRedisTemplate 기반), `RoomService` (UUID 발급 + GameRegistry
검증 + Lua 호출). 원자화는 `room_create.lua` · `room_join.lua` · `room_leave.lua`
3종으로 처리하고 Lua 가 정수 코드(음수 = 에러, 비음수 = 카운트)를 반환. 입장 시
capacity 도달하면 status WAITING→IN_GAME 자동 전이 + `rooms:open` ZSET에서 ZREM
(Phase 4 의 게임 엔진 호출은 별도). `/api/rooms` 5개 엔드포인트, `RoomController`
는 도메인 로직 0 (서비스 위임만). 동시성은 `RoomServiceConcurrencyIT` 가 9 스레드
동시 입장으로 capacity=4 위반 0건 검증.

## D-11 (2026-05-13) — Phase 2c: GameRegistry + Tichu 메타 + 카탈로그 API

`domain.game.core` 에 `GameDefinition` 인터페이스, `GameRegistry`(Spring DI로 Bean
자동 수집), `GameStatus` enum, 그리고 Phase 3 에서 채울 `GameEngine` · `GameContext`
· `GameAction` · `GameEvent` 빈 스켈레톤을 둔다. `GameDefinition` 은 일부러
**non-sealed**: D-06 의 "새 게임 = Bean 추가만" 약속을 보존하고, 테스트에서 fake
정의를 만들 수 있게. `TichuGameDefinition` 은 메타데이터(이름/설명/인원/AVAILABLE)만
진짜이고 `newEngine` 은 Phase 3 표시용 `UnsupportedOperationException`. `/api/games`
와 `/api/games/{id}` 는 인증 필요(api.md ○ 마커와 일치)로 SecurityConfig 의 임시
permitAll 제거. `GameNotFoundException` 은 `GAME_NOT_AVAILABLE` 로 404 매핑.
