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

## D-38 (2026-05-14) — Phase 6 시연 검증 (A 방향) — README 체크리스트 정착

Phase 6 의 19개 커밋이 main 위에 올라간 시점에서 사용자 직접 클릭 시연으로 UX/운영
기능의 실제 동작을 확인할 단계. Explore 1회로 사전 점검 4축 (DomainEventBus
polymorphic 직렬화 / phoenixSingle 전파 / spectatorIds 클라 매핑 / MakeWishModal
자동 dismiss) 검토 — 1~3 OK, 4 는 낮은 위험 (다중 wish 기능 도입 시 재발 가능,
현재 흐름 영향 없음). README 에 "Phase 6 시연 체크리스트" 섹션 추가 — 5 시나리오
(Mahjong 소원 / Dragon 양도 / Phoenix 단독 SINGLE / 관전 모드 / 멀티 인스턴스
Redis fan-out) + 운영 카운터 확인 + 로그 MDC 확인 + 실패 시 보고 가이드. 시연 중
이슈가 발견되면 후속 청크로 즉시 픽스. 모든 시나리오 통과 시 Phase 6 의 진정한
종료 — 남는 항목은 Option B / IN_GAME 자동 목록 / 잔여 21색 / docker-compose multi /
다중 wish 흐름 등 별도 사이클 보류 건.

## D-39 (2026-05-15) — Phase 7-1: MySQL 8 → PostgreSQL 16 마이그레이션

Fly.io + Upstash Redis 배포를 위해 RDB 를 PostgreSQL 로 통일. Fly Postgres /
Supabase / Neon 등 매니지드 옵션이 모두 Postgres 기반이고 친구 시연 규모에서
무료/저비용 티어가 더 풍부하다. **변경 범위**: V1 마이그레이션 SQL 문법
(`AUTO_INCREMENT` → `GENERATED ALWAYS AS IDENTITY`, `JSON` 컬럼 → `TEXT` —
앱이 Jackson 으로 직렬화하므로 DB 측 JSON 연산 불필요, `ENGINE/CHARSET` 절 제거,
복합 인덱스를 별도 `CREATE INDEX` 로 분리), JDBC 드라이버 (`mysql-connector-j` →
`org.postgresql:postgresql`, `flyway-mysql` → `flyway-database-postgresql`),
Testcontainers (9 IT 의 `MySQLContainer` → `PostgreSQLContainer`),
`docker-compose.yml` 의 mysql 서비스 → postgres:16-alpine, `application.yml`
의 jdbc URL/driver. **불변**: D-02 의 users 테이블 컬럼 화이트리스트, D-01 의
Server-Authoritative / State Hiding / 모듈러 모놀리스 경계. 본 변경은 Phase 7
배포 작업의 첫 청크이며, 7-2 (Dockerfile + fly.toml), 7-3 (Upstash + prod
profile + Spring static serving), 7-4 (클라 번들 통합) 이 뒤따른다.

## D-59 (2026-05-15) — Phase 10D: Rule invariant 검증 + Dog 카드 보존 엔진 fix

`TichuInvariantChecker` (pure function, 테스트 전용 호출) 신설. 4 invariant:
(1) 카드 보존 — 모든 hand + tricksWon + currentTrick.accumulated = 56, 카드 unique,
(2) finishedOrder 유일성 (1..4, 중복 없음), (3) 턴 단조성 (currentTurnSeat 가 active
player — Dragon pending 예외), (4) 활성 wish rank 범위 [2,14]. 점수 보존
(`cardPointsSum == 100`) 은 `ScoreCalculator` 가 이미 보장하므로 별도 X.

`BotMatchSimulationIT` 의 매치 종료 후 stateStore 마지막 state 에 checker 호출.

**본 invariant 가 catch 한 엔진 버그**: Dog 카드 단독 lead 후 누구의 tricksWon 에도
들어가지 않아 라운드 종료 시 카드 보존 위반 (count=55). `TichuEngine.applyPlayCard`
Dog 분기 fix — Dog 카드를 nextLead 의 tricksWon 에 보존 (점수 0 이라 score 영향 X).
표준 Tichu 룰의 "discard pile" 부재를 우회. 단위 테스트 8개 (TichuInvariantCheckerTest)
+ BotMatchSimulationIT 11 매치 invariant 만족 그린. 전체 회귀 1m20s 그린.

`docs/rules-tichu.md` §8.2 (Dog) 의 동작 명세에 "Dog 카드는 nextLead 의 tricksWon
으로 보존 (점수 0)" 추가 필요.

## D-58 (2026-05-15) — Phase 10C: Wish follow 강제 마감

`ActionValidator.validatePlayCard` 의 wish 분기 (line 76-86) 가 "Phase 3f 에서 refinement"
주석으로 follow 시 deferred 였던 부분 마감. `WishFulfillmentChecker.canPlayWishRank`
(pure function) 신규 — 1차 구현 범위: 단일/페어/트리플 + Phoenix 와일드 페어/트리플.
콤보 (STRAIGHT/FULL_HOUSE/CONSECUTIVE_PAIRS/BOMB) 미포함. 표준 Tichu 룰상 모든 합법
핸드 포함해야 하나, 친구 시연 규모에서는 단일/페어/트리플로 충분 — 향후 D-NN 으로
콤보 확장 가능.

follow 차례 분기:
- 보유 wish rank 없음 → 자유
- 보유 + 플레이에 포함 → fulfill (TichuEngine.applyPlayCard 이 이미 처리)
- 보유 + 미포함 + canPlayWishRank true → WISH_NOT_FULFILLED reject
- 보유 + 미포함 + canPlayWishRank false (top 너무 강함 등) → 자유

테스트 12개 추가 (WishFulfillmentCheckerTest 8 + ActionValidatorTest 4). 봇
시뮬레이션 IT 도 새 wish 룰 하에서 그린 — RandomBotPolicy 가 LegalActionEnumerator
→ ActionValidator 필터로 자동 적응. 전체 서버 회귀 1m24s 그린. `docs/rules-tichu.md`
의 §9 갭 항목 해소.

## D-57 (2026-05-15) — Phase 10B: 특수 카드 결정적 통합 시나리오

`TichuSpecialCardScenarioTest` 15개 결정적 시나리오 추가 (TichuEngineRoundSimulationTest
패턴 재사용 — Spring 부트 없이 PlayerState/TrickState 수동 조립). 분류: Mahjong+Wish
(4) / Dog (3) / Dragon (3) / Phoenix (3) / BOMB 인터럽트 (2). 각 시나리오가 `docs/rules-tichu.md`
의 해당 섹션에 lock in 된 동작을 회귀 보장. 작성 중 Phoenix follow over K 시나리오에서
1장씩 손패면 3명 완주로 라운드 종료 → state cast 실패 발견 → 손패 2장으로 보강.

Dragon 양도 후 점수 이전 (`recipient.tricksWon += accumulated`) 의 첫 명시적 단위
테스트 — D-56 갭 #2 해소. Dog 가 wish 활성 시 reject 되는 동작 (단독이라 wish rank
미포함) 은 본 테스트 셋에선 다루지 않음 — 10C wish follow 마감과 함께.

## D-56 (2026-05-15) — Phase 10A: 티츄 룰 명세 문서

`docs/rules-tichu.md` 신설 — 14 섹션의 룰 ↔ 코드 mapping single source of truth.
카드/덱, 라이프사이클, 선언, 패스, 핸드 8 타입 + Phoenix 와일드, 핸드 비교, 특수
4종 (Mahjong/Dog/Phoenix/Dragon), wish 강제, BOMB 인터럽트, 트릭 종료, 라운드
종료, 점수, 매치 종료 각각에 "코드 경로 + 테스트 + 갭" 표기. 작성 중 발견된
명시 갭:
1. Phoenix 가 4등 손에 남은 채 -25 가 상대팀으로 이전되는 단위 테스트 부재
2. Dragon 양도 후 점수 이전 (`recipient.tricksWon += accumulated`) 단위 테스트 부재
3. Wish follow 강제 미구현 (ActionValidator:82 "deferred" 주석)
4. Wish + BOMB 인터럽트 fulfillment 처리 명시 부재
5. Dog 가 wish 활성 시 reject 되는 동작 (의도) 의 명시 테스트 부재
6. Mahjong 을 콤보 일부로 낸 후 wish 가능 여부 — 현재 ActionValidator 는 단독
   Mahjong 만 인정 (`currentTop.cards == [Mahjong]`).

갭 1-5 는 10B/10C/10D 에서 해소. 갭 6 은 표준 Tichu 룰상 Mahjong 콤보가 거의
없으므로 (스트레이트 1-2-3-4-5 정도) 보류 — 향후 사용자 요청 시 별도 결정.

본 문서는 룰 변경 시 코드/테스트와 같은 commit 으로 갱신.

## D-55 (2026-05-15) — Phase 9D: 클라 솔로 모드 UI

`Room` TS 타입에 `fillWithBots:boolean`, `botSeats:number[]` 추가 (서버 derived).
`roomsApi.create` 시그니처 `opts.fillWithBots` 옵션. LobbyPage 의 방 생성 폼에 "🤖
빈 좌석 봇으로 채우기" 체크박스 + 방 리스트의 솔로 방에 "🤖 솔로" 배지. `SeatAvatar`
는 `isBot` prop 시 🤖 이모지 (아바타 이미지 미사용) + 살짝 다른 배경. `GameTable`
헤더에 `fillWithBots=true` 면 "🤖 솔로 모드 (봇 N명)" 배너. 클라 47/47 회귀 + tsc
type-check 그린.

## D-54 (2026-05-15) — Phase 9G: GitHub Actions CI

`.github/workflows/ci.yml` 도입. 3 job: (1) Server — JDK 25 Temurin + Gradle cache +
`:server:test` 풀 셋 (단위 + Testcontainers IT, Ubuntu runner 의 기본 Docker 사용),
(2) Client — Node 20 + npm cache + `build:check` + `test` + production build, (3)
bundle-jar — `bootJar -PbundleClient` 후 jar 안 static 자산 동봉 검증 (regression
prevention for Phase 7-4 통합). Trigger: push to main + PR. Concurrency group 으로
같은 branch 의 이전 run 은 cancel-in-progress (PR force-push 시 자원 절약). 테스트
실패 시 reports artifact 7일 보존. 실제 배포 게이팅은 사용자가 main 보호 규칙으로
설정 권장 (D-31 의 main 인비전 정책 연속).

## D-53 (2026-05-15) — Phase 9F: Husky-free pre-commit

`.husky/pre-commit` 스크립트만 두고 npm husky devDep 도입은 회피 — 가벼움 우선.
활성화: `git config core.hooksPath .husky` 1회. 스크립트 내용: 클라 `tsc --noEmit`
(`build:check` script 추가) + `vitest run` + 서버 `./gradlew :server:compileJava
:server:compileTestJava` (단위/IT 는 CI 가 담당 — Docker 의존성 회피). 평균 30초 이내.
우회: `--no-verify`. node_modules 없으면 클라 단계 silently skip — 처음 clone 한
사용자가 차단되지 않음. README 의 "코드 수정 검증 흐름" 섹션에 활성화 안내.

## D-52 (2026-05-15) — Phase 9E: 봇 매치 풀-게임 시뮬레이션 IT + 엔진 버그 fix

`BotMatchSimulationIT` (`@SpringBootTest` + Testcontainers) 가 4 봇 매치를 매치 종료까지
진행: (1) 단일 매치 60s 내 완주, (2) 10회 연속 무 회귀 (테스트 property
`mirboard.bot.simulation-count` 로 조정). 봇 딜레이 0 + 시드 12345 고정. 본 IT 가
실제로 엔진 버그를 catch: **Dog 카드 플레이 시 파트너가 완주한 경우 finished 좌석이
새 트릭의 리더가 되어 게임이 데드락**. `TichuEngine.applyPlayCard` 의 Dog 분기에
`nextActiveSeat(players, partner)` 적용으로 fix — 회귀 안전망의 가치를 즉시 증명.
`LegalActionEnumerator` 에 트리플 카드 후보 추가 (wish 강제 상황에서 단일+페어만
으론 합법 출구 부재) + RandomBotPolicy 의 PassTrick 가중치 균등 (PlayCard 가 많을수록
plays 비율 ↑ → 라운드 종료 보장). 전체 서버 회귀 그린.

## D-51 (2026-05-15) — Phase 9C: BotScheduler — 서버 사이드 랜덤 봇 AI

`LegalActionEnumerator` (pure function) 가 phase 별 후보 액션 생성 후 `ActionValidator`
통과만 합법 분류 — 검증 로직 단일 소스 (엔진) 위임. `RandomBotPolicy` 는 시드 가능
Random 으로 합법 액션 중 1개 선택 (PlayCard 후보 있으면 50% pass 선호 휴리스틱).
`BotScheduler` @Component 는 가상스레드 풀에서 비동기 실행: 락 획득 → state load →
봇 차례 감지 (Dealing 안-ready / Passing 안-submitted / Playing currentTurnSeat 또는
Dragon-give 대기) → 액션 선택 → `TichuEngine.apply` → save → broadcast → 재귀. 무한
루프 가드 5000 회. 락 contention 시 50ms 후 재시도. `Thread.sleep(200ms)` 으로 인간
페이스 흉내 (테스트는 0). 트리거 hook 2곳: `TichuRoundStarter.startRound` 직후 (라운드
시작), `GameStompController.handleAction` 끝 (인간 액션 후). 순환 의존 끊기: `@Lazy`
BotScheduler. `GameStompController.handleRoundEnd` 를 `MatchProgressService` 로 추출 —
GameStompController/BotScheduler 모두 같은 라운드 종료 후속 처리 (점수 누적, 매치 종료
또는 다음 라운드) 공유. `MatchResultRecorder.onMatchCompleted` 진입 시 봇 1명이라도
있으면 ELO/win-lose 갱신 skip (rating 인플레이션 방지). 단위 테스트 12개 (
LegalActionEnumerator 8 + RandomBotPolicy 4) + 서버 전체 회귀 그린.

## D-50 (2026-05-15) — Phase 9B: Room "봇으로 채움" 토글

`Room` 레코드에 `fillWithBots:boolean` + derived `botSeats:int[]` 필드 추가.
`room_create.lua` 의 ARGV 마지막에 fillWithBots 받아 HASH 에 저장. `RoomService.createRoom`
시그니처 마지막에 boolean 인자 추가 — true 면 createRoom 직후 `BotUserRegistry.takeBots(capacity-1)`
로 빈 좌석에 봇 자동 join. 마지막 봇 join 이 capacity 도달 → 기존 IN_GAME 전이 +
GameStartingEvent 발행 흐름 그대로 (room_join.lua 변경 없음 — 봇도 capacity 검증을 동일하게
통과). `RoomController.CreateRequest` 에 `Boolean fillWithBots` payload 필드 추가 (기본
false). `RoomRepository.findById()` 가 playerIds 중 봇 user id 좌석을 `botSeats` 로
계산해서 노출 → 클라가 어느 자리가 봇인지 알 수 있음. 별도 솔로 진입점 없이 일반 방
옵션이라 유연성 ↑. IT 2개 추가 (fillWithBots=true / false), 서버 전체 회귀 그린.

## D-49 (2026-05-15) — Phase 9A: 봇 사용자 분류 플래그 + 시드 봇 4명

솔로 모드 (Phase 9) 를 위해 V3 마이그레이션으로 `users.is_bot BOOLEAN NOT NULL
DEFAULT false` 컬럼 + `bot_north/east/south/west` 시드 4명 INSERT. password_hash
는 `__bot_no_login__` sentinel — bcrypt 형식이 아니라 어떤 평문으로도 로그인
불가. D-02 의 "개인정보 최소화" 원칙 재해석: `is_bot` 은 분류 플래그 (식별/연락
정보 아님) 이므로 컬럼 화이트리스트에 추가 가능. `BotUserRegistry` @Component
가 부팅 시 봇 4명을 캐시 (id 오름차순), 9B 의 자동 join + 9C 의 BotScheduler
가 사용. 봇은 STOMP 세션 없이 서버 내부에서만 동작하므로 JWT 발급 우회. 단위
테스트 6개 (`BotUserRegistryTest`) 그린 + `AuthFlowIntegrationTest` IT 그린
(V3 가 인증 흐름 깨지 않음 검증).

## D-48 (2026-05-15) — Phase 8F 변경: 트럼프 풍 SVG 카드셋 + 자연 색 매핑

D-46 의 AI WebP 전략을 **사전 생성 SVG** 로 교체. `scripts/generate-cards.mjs`
가 52장 + `back.svg` + ornate J/Q/K 변형 12장을 생성한다. Tichu 도메인 슈트는
유지하고 시각만 트럼프 풍으로 매핑: JADE→♣Club (#2d8c4e), SWORD→♦Diamond
(#2f6fe0), STAR→♥Heart (#d4253c), PAGODA→♠Spade (#1a1a1a). `cardAssetSrc` 와
`SeatAvatar` 외 자산 URL 확장자가 `.webp` → `.svg` 로 변경 (캐릭터 자산은 별도
이슈로 `.webp` 유지). 이유: (1) AI 이미지 일관성 보장이 어렵고 PoC 검토 비용
큼, (2) SVG 는 결정적·작고·고해상도, (3) 56장 일괄 생성으로 톤 차이 0. ornate
변형은 `client/public/cards/face-ornate/` 에 두어 향후 비교/선택용. 단위 테스트
`cardAssetSrc.test.ts` 의 기대값 `.webp` → `.svg` 갱신, 클라 47/47 회귀 그린.
*변경 → D-46*

## D-47 (2026-05-15) — Phase 8G 마감: 하이핸드 이펙트 (BOMB/STRAIGHT_FLUSH_BOMB)

`effectStore` (Zustand) 가 active effect 1개 관리, `tichuStore.applyEvent` 의
PLAYED 분기에서 `effectForHandType(p.hand.type)` 가 BOMB/STRAIGHT_FLUSH_BOMB 이면
`useEffectStore.trigger(kind)` 호출. `EffectsOverlay` 컴포넌트가 active 구독해
fixed inset:0 + zIndex 9999 로 화면 전역 오버레이 (BOMB: 빨강 플래시 + 12개 노랑
spike + 주황 원 / STRAIGHT_FLUSH_BOMB: 보라 + 보라 원). CSS keyframes
(`mirboard-fx-flash/burst/pop`) 으로 1.8초 동안 fade-out. `useSfx` hook 가
mp3 재생 (`/sfx/bomb.mp3`, `/sfx/straight-flush.mp3`) — 자산 부재 시 silent
fallback. mute 토글은 localStorage 영속화 (`mirboard.sfxMuted`). 자동재생 차단
시도는 `.play().catch(() => {})` 로 silent — STOMP 수신 직후라 첫 사용자 클릭
이전이면 차단되지만 죽지 않음. GameTable 헤더에 🔊/🔇 토글 버튼. 단위 테스트
5개 (`effectStore.test.ts`) + tichuStore PLAYED BOMB/non-bomb 분기 2개 추가.
실제 mp3 자산은 사용자가 채워야 함 (`client/public/sfx/README.md` 가이드). 클라
47/47 회귀 그린.

## D-46 (2026-05-15) — Phase 8F 마감: 카드/캐릭터 이미지 + graceful fallback

`cardAssetSrc(card)` helper 가 `Card → /cards/{suit}-{rank}.webp` URL 매핑 (특수
카드는 `/cards/{special}.webp`). `CardChip` 이 `<img onError>` 로 이미지 시도 →
실패 시 기존 텍스트 글리프 fallback — 사용자가 AI 생성 자산을 점진적으로 채워도
게임이 깨지지 않음. `SeatAvatar` 는 `/characters/seat-{0..3}.webp` 시도 →
fallback 시 좌석 번호 + 팀 색 border. CSS `.card-chip-img` 가 이미지 모드에서
56×78px (5:7 비율) 으로 카드 표시, 텍스트 모드는 기존 padding/border. 마스터
prompt 템플릿 + 슈트별 색상 + 명명 규칙은 `docs/assets/card-prompts.md` 에 정착
— PoC 4장 → 사용자 승인 → 나머지 52장 일관성 보장 전략. `clientBuild` Gradle
task 의 inputs 에 `client/public` 추가 — 자산 변경 시 cache 무효화. 실제 56장 +
캐릭터 4 + 보드 1 자산은 사용자가 AI 로 생성해서 채워야 하는 부분 (코드 측은
자산 0개 상태에서도 전 테스트 통과). 단위 테스트 3개 (`cardAssetSrc.test.ts`)
+ 클라 40/40 회귀 그린.
*변경 → D-48*

## D-45 (2026-05-15) — Phase 8E 마감: 보드 풍 테이블 레이아웃

`.table-seats` (grid 4열) 를 `.table-arena` (relative + absolute 좌석) 로 교체.
React 측 본인 시점 매핑: `viewIdx = (seat - mySeat + 4) % 4` → S/W/N/E
className 분기 (본인 항상 S 하단, 파트너 N 상단, 적팀 W/E). 중앙 currentTop 은
`.table-center-trick` 가 absolute 중앙 — 기존 별도 `.trick` 영역은 통째로 제거하고
lead 대기/시트 번호/hand-type/Phoenix 배지를 모두 중앙 영역에 통합. 다크 그린
radial gradient 로 카지노 테이블 분위기. 모바일 fallback (max-width: 640px)
에서는 absolute 해제 + grid 4열 + 중앙 트릭은 grid 5번째 칸 — 좌석 좁아짐 방지.
본인 손패 hover 시 translateY(-6px) + scale 1.05 (선택 시 -10px) 로 카드 떠오르기.
실제 트럼프 카드 이미지 + 부채꼴 fan-out 은 8F 와 함께 도입 예정 (현 SortableHand
는 가로 일렬 유지). 클라 빌드 +1.5KB CSS, 37 단위 테스트 회귀 그린.

## D-44 (2026-05-15) — Phase 8D 마감: ELO + 6단계 티어

V2 마이그레이션 (`users.rating INT NOT NULL DEFAULT 1000`) — D-02 schema constraint
통과 (식별정보 아닌 derived 성적 집계). `EloCalculator.applyMatch(teamA, teamB,
winnerIsTeamA)` 가 팀 평균 rating 기준 기대 승률 산출 → 한 팀 두 명 동일 +/- delta.
K-factor 32 (기본), 30게임 미만 신규는 40. tier 는 DB 컬럼 X — `Tier.fromRating(int)`
로 6구간 derived (BRONZE/SILVER/GOLD/PLATINUM/DIAMOND/MASTER). `MatchResultRecorder`
가 win/lose 증분 *이전*에 rating + gamesPlayed 수집해서 K-factor 임계 정확하게 적용,
같은 `@Transactional` 안에서 `userRepo.updateRating(userId, newRating)` 호출. 새
endpoint `GET /api/users/{userId}/stats` 가 rating + tier (derived) + win/lose 반환
— 이메일/전화 등 식별 정보 노출 0건. 클라는 `TierBadge` 컴포넌트 + Hub 헤더에
본인 티어/rating/전적 표시. 통합 테스트: `MatchResultRecorderIT` 에 ELO 검증 추가
(신규 4명 동일 rating → ±20), `UserStatsIntegrationTest` 2개 (신규=BRONZE/1000,
unknown user 404), `EloCalculatorTest` 단위 6개 (K-factor 임계 / 동일 rating / 업셋
/ 빈 팀 거절 / tier 구간 boundary).

## D-43 (2026-05-15) — Phase 8C 마감: 팀 배정 정책 (SEQUENTIAL/RANDOM/MANUAL)

`Room.teamPolicy` enum 3개 — `SEQUENTIAL` (입장 순서, 기본), `RANDOM` (4번째 join
직후 `Collections.shuffle`), `MANUAL` (enum 만 예약, 서버 동작은 SEQUENTIAL 동일 —
후속 청크에서 호스트 드래그 UI 도입 시 분기). `room_create.lua` 가 ARGV[7] 로
teamPolicy 수신, `RoomRepository.create()` 시그니처 확장, `findById` 가 누락 컬럼
시 SEQUENTIAL 기본값 (기존 방과 호환). RANDOM 셔플은 `RoomService.joinRoom` 의
IN_GAME 분기에서 1회만 — Lua 가 capacity 막은 직후 `repository.replacePlayerOrder`
(DEL + RPUSHALL, 신규 join 동시성 없음으로 안전). 호스트 정책 변경 endpoint
(`PUT /api/rooms/{id}/team-policy`) 는 WAITING 한정 + `NotHostException`/
`GameAlreadyStartedException` 가드. 클라는 `RoomPage` WAITING 섹션에 호스트만
드롭다운 (참가자는 readonly Badge). `RoomService` 생성자 분리 (`@Autowired` 명시 +
시드 고정 Random 주입용 보조 생성자 — Spring 4.0 의 No default constructor 오류
해결). 통합 테스트 6개 (`RoomTeamPolicyIntegrationTest`).

## D-42 (2026-05-15) — Phase 8B 마감: 인-게임 채팅 (인메모리)

`@MessageMapping("/room/{roomId}/chat")` STOMP 핸들러 + `/topic/room/{roomId}/chat`
broadcast. 로비 채팅 ({@link LobbyStompController}) 패턴 답습 — `StompPublisher`
경유로 멀티 인스턴스 fan-out. 멤버 검증은 `RoomService.isParticipantOrSpectator`
재사용 (참여자 + 관전자 모두 송수신 허용, 비-멤버는 silent drop). 영속화 없음 —
재접속 시 과거 메시지는 못 봄 (MVP 정책, 영속화는 향후 별도 청크). 클라는
`useRoomChatStore` (Zustand) 가 메시지 큐 + 안 읽은 카운트 관리, `chatPanelOpenRef`
ref 로 패널 열림 여부 전달해서 패널이 열려있는 동안엔 unread 가 안 늘게 함.
`RoomChat` 사이드패널 + GameTable 헤더에 💬 토글 + unread 뱃지. 8B-1 (서버), 8B-2
(클라 컴포넌트), 8B-3 (토글/뱃지) 한 청크에서 처리. 통합 테스트 2개
(`RoomChatStompIntegrationTest`) 가 멤버 fan-out + 비-멤버 drop 검증, 클라 단위 5개
(`roomChatStore.test.ts`) 가 reset/unread/cap 검증.

## D-41 (2026-05-15) — Phase 8A 마감: joinOrReconnect 분기 + 호스트 abort

직접 링크 진입 자동 분기 (`POST /api/rooms/{id}/join-or-reconnect`) — 본인이
이미 playerIds 에 있으면 `RECONNECTED` (Redis 변경 없음, 좌석 보존), WAITING 방
빈 자리면 `JOINED`, 그 외 (IN_GAME / capacity full) 는 `SPECTATING` 으로 자동
흡수. State Hiding 의 1차 방어선 — 비-참여자가 player 목록에 절대 들어가지
않음. 통합 테스트 (`RoomJoinOrReconnectIntegrationTest` 7개) 가 5번째 사용자
IN_GAME 방 진입 시 `privateHand=null` 임을 검증. 호스트 abort (`POST /api/rooms/{id}/abort`,
무한 재접속 정책 하의 유일한 탈출구) 는 `NotHostException(403)` / `GameNotInProgressException(409)`
가드. 클라는 RoomPage 진입 시 자동 호출 + `ReconnectBanner` 가 STOMP `connected=false`
1초 이상이면 노란 배너, 3분 이상이면 "호스트가 강제 종료할 수 있음" 안내.

## D-40 (2026-05-15) — Phase 8 진입 계획: 포스트-배포 UX/기능 확장 7개 sub-phase

Phase 7 배포 직후 사용자가 요청한 10개 항목 (보드 풍 레이아웃, 트럼프 카드, ELO 등급,
팀 옵션, 재접속/직접 링크, 인-게임 채팅, AI 생성 자산, 하이핸드 이펙트 등) 을 Phase 6
패턴으로 7개 sub-phase 로 분할: **8A** 재접속 + 직접 링크 자동 합류 (joinOrReconnect
분기 + 호스트 abort) → **8B** 인-게임 채팅 (인메모리, 로비 채팅 패턴 답습) → **8C**
팀 옵션 (Room.teamPolicy SEQUENTIAL/RANDOM/MANUAL) → **8D** ELO + 6단계 티어
(users.rating INT DEFAULT 1000 V2 마이그레이션, K=32, 티어 derived) → **8E** 보드 풍
레이아웃 (좌석 본인 시점 회전 + 모바일 가로 일렬 폴백) → **8F** AI 이미지 정적 번들
(client/public/ 트럼프 풍 카드 56 + 캐릭터 4 + 보드 1, 4장 PoC 우선) → **8G** 하이핸드
이펙트 (BOMB/STRAIGHT_FLUSH_BOMB SVG 애니메이션 + 사운드, mute 토글). **불변**: D-02
의 users 컬럼 화이트리스트 (rating 은 식별정보 아님으로 통과), D-01 의 State Hiding
(8A 분기 오류 시 손패 노출 위험이 본 Phase 최대 위험). **진입 조건**: Phase 7-5
(배포 검증) 그린 이후 8A 시작.

## D-37 (2026-05-14) — Phase 6D 마감: 멀티 인스턴스 (Redis Pub/Sub) 추상화

단일 인스턴스 가정 (D-13 의 SimpleBroker + Spring ApplicationEventPublisher 의
in-process 한계) 을 해소하면서도 단일 인스턴스 동작은 그대로 유지하는 추상화 계층.
**(6D-1)** `MessageGateway` 인터페이스 + `InMemoryMessageGateway` (default,
matchIfMissing) / `RedisMessageGateway` (mirboard.messaging.gateway=redis) 구현체.
패턴: publish(channel, json) / subscribe(pattern, handler). 패턴 매칭은 Redis
psubscribe 의 부분집합 (`*` 와일드카드만). **(6D-2)** STOMP broadcast 를
`SimpMessagingTemplate` 직접 호출 대신 `StompPublisher` → gateway →
`StompMessageRelay` (각 인스턴스의 @PostConstruct subscribe) 흐름으로 전환.
`GameEventBroadcaster`, `RoomLobbyEventPublisher`, `LobbyStompController` 의
broker 의존 제거. LobbyStompController 의 `@SendTo` 도 명시 publish 로 — Spring
자동 broker 전송과 gateway 경로가 충돌하지 않게. **(6D-3)** `DomainEventBus` 도입.
도메인 이벤트 (`RoomChangedEvent` / `GameStartingEvent` / `TichuMatchCompleted`)
발행을 `ApplicationEventPublisher` → `DomainEventBus.publish` 로 전환. 발행 시
(1) local 즉시 + (2) Redis Pub/Sub fan-out, 수신 측은 `instanceId` 로 self-skip 후
local `ApplicationEventPublisher` 로 변환 — 기존 `@EventListener` 들은 변경 없이
멀티 인스턴스에서도 호출. **(6D-4)** README 에 멀티 인스턴스 시연 가이드
(MIRBOARD_PORT 분리 + MIRBOARD_MESSAGING_GATEWAY=redis). docker-compose `multi`
프로파일은 Dockerfile 없이 표현이 까다로워 로컬 두 프로세스 패턴으로 대체.
한계: 발행 인스턴스 재시작 시 in-flight 이벤트 유실 가능 (instanceId 기반 dedup
의 자연 결과). MVP 범위 밖이라 보류.

## D-36 (2026-05-14) — Phase 6C 마감: 디자인 토큰 + 공용 컴포넌트 + 모바일

styles.css 단일 + 인라인 혼합 + 색상 하드코딩이 깨지기 쉬워 다음 3축으로 정리.
**(6C-1)** `styles/tokens.css` 신규 — color/space/radius/shadow/font-size 토큰을
`:root` 의 CSS custom property 로 묶음. 다국어/멀티테마 도입 시 `[data-theme="..."]`
분기만으로 갱신. 핵심 16색 hex (bg/text/border/accent/success/danger/domain) 를
sed 로 `var(--*)` 일괄 치환. 잔여 21색 (페이지별 특수 톤) 은 별도 사이클.
**(6C-2)** `components/` 디렉토리 신설 — `Button` (variant=default/primary/danger/subtle),
`Input` (label 있으면 stacked), `Modal` (backdrop + 카드 + actions), `Stack` (flex
gap=토큰 인덱스), `Badge` (tone=default/success/warning/danger/phoenix/accent).
글로벌 button 스타일을 base 로 두고 variant 클래스만 부착하는 패턴 — 새 컴포넌트
도입에 따른 스타일 폭증 최소화. **(6C-3)** Login/Register/Room 페이지의 인라인
`<input>`/`<button>` 패턴을 공용 컴포넌트로 치환. MakeWishModal / GiveDragonTrickModal
도 공용 `Modal` 로 래핑 → 도메인 모달이 콘텐츠만 children 으로 주입. Phoenix
배지는 `Badge tone="phoenix"` 로 통합. **(6C-4)** `@media (max-width: 720px)` 에서
좌석 그리드 4열 → 2열, 헤더 wrap, pass-slots wrap, 게임 카탈로그 1열. `@media (max-width:
480px)` 에서 카드 padding 축소 + 모달 좁힘 + 소원 rank grid 7→5열. 전면 리디자인은
하지 않음 — 토큰/공용/모바일 기반만 마련하고 비주얼은 그대로.

## D-35 (2026-05-14) — Phase 6A 마감: 운영 강화 + 관전 모드

운영성 / 관측성 / 관전자 3축을 한 사이클에 묶어 마감. **(6A-1)** MdcKeys AutoCloseable
헬퍼 + JwtAuthFilter / GameStompController.onAction 에 try-with-resources (Java 25 의
unnamed `_` 활용) 로 userId / roomId / eventId 자동 부착. application.yml 의
`logging.pattern.console` 에 `%X{...}` 추가 — 로그 한 줄마다 `[user=N room=R event=E]`.
**(6A-2)** RoomService / GameStompController.handleRoundEnd 의 라이프사이클 분기에
INFO 로그 추가. TichuRoundStarter / MatchResultRecorder 는 이미 적절. 액션 reject 도
INFO 로 추적. **(6A-3)** spring-boot-starter-actuator + micrometer-registry-prometheus
의존성 추가. management.endpoints 에 health/info/metrics/prometheus 노출, SecurityConfig
의 `/actuator/**` permitAll — 운영 전 내부망 격리 또는 management.server.port 분리 필요
(TODO 주석). **(6A-4)** MirboardMetrics 컴포넌트 — 6개 도메인 카운터
(`mirboard.room.created`, `joined`, `mirboard.game.started{gameType=TICHU}`,
`round.completed`, `match.completed`, `action.rejected`). 생성자에서 사전 등록되어
첫 스크래핑부터 시계열 노출. **(6A-5)** Room.spectatorIds 추가 + Redis SET 단순
SADD/SREM 으로 원자성 부담 없는 관전 추가/제거. `POST/DELETE /api/rooms/{id}/spectate`,
resync 검증을 "참여자 OR 관전자" 로 확장하되 관전자에겐 PrivateHand=null. STOMP 인가는
별도 추가 없이 user destination 의 본인 큐 라우팅으로 자연 격리, 액션 송신은 기존
seat<0 → NOT_IN_ROOM 으로 reject. **(6A-6)** 클라 측은 RoomPage 가 spectatorIds 감지해
GameTable 에 `spectator` prop 전달, 손패 영역/액션 버튼/모달 모두 spectator 분기로 숨김.
LobbyPage 에 "방 ID 로 관전 진입" 입력 박스 — IN_GAME 방 목록 자동 노출은 서버측
별도 추적 ZSET 필요해서 별도 사이클로. 통합 테스트 4건 추가 (spectator resync /
PrivateHand 부재 / 자기 자신 관전 거절 / stop spectating).

## D-34 (2026-05-14) — Phase 6E 마감: 티츄 룰 UX 마감 (MakeWish / GiveDragonTrick / Phoenix)

서버 룰 엔진 (D-16, D-17) 에서 이미 완비된 MakeWish · GiveDragonTrick · Phoenix
단독 SINGLE 처리에 대응되는 클라이언트 UI 가 누락되어 있었음. 본 Phase 에서 추가:
**(6E-1)** `MakeWishModal` — Mahjong 보유자가 단독 리드 직후 rank 2~14 선택 또는 건너뛰기.
트리거 조건은 `currentTopSeat==mySeat && currentTop.cards==[Mahjong] && activeWishRank===null`,
`wishContextKey` 변경 시 dismissed 자동 리셋. **(6E-2)** `GiveDragonTrickModal` — Dragon 단독으로
트릭을 이긴 직후 본인으로 turn 이 다시 돌아오는 상태 (`closeTrickAndContinue` 가 dragonWon 시
`TrickTaken` 대신 `TurnChanged(taker)` 만 발행) 를 감지해 상대팀 두 좌석 중 선택 모달.
`opponentSeatsOf(mySeat)` 헬퍼는 Team 룰 (짝수/홀수) 로 분기. **(6E-3)** Phoenix 단독 SINGLE
배지 — `currentTop.phoenixSingle` true 시 "Phoenix +0.5" 배지 + 비교 룰 툴팁. **(6E-4)** 이벤트
리듀서 검증 — `TRICK_TAKEN` 후에도 `activeWishRank` 유지 / `DRAGON_GIVEN` 은 seq 만 진행하고
점수 패치는 동반 `TRICK_TAKEN` 에 위임하는 회귀 테스트 추가. 모달 스타일은 `.modal-backdrop /
.modal / .modal-actions` 공용 클래스로 도입 — Phase 6C 의 공용 컴포넌트 분리 시 그대로 재사용
가능.

## D-33 (2026-05-14) — Phase 6 진입: E → A → C → D 순서 + Option B 보류

MVP (Phase 1~5) 마감 후속으로 4개 후보를 채택. 순서는 사용자 가치 우선 — **E (티츄 룰 UX
마감)** → **A (운영 강화 + 관전 모드)** → **C (UI 디자인 토큰 + 공용 컴포넌트)** → **D
(멀티 인스턴스, Redis Pub/Sub 기반)**. **Option B (새 게임 추가) 는 본 사이클에서 제외**
— GameRegistry 패턴 실증은 Phase 6 마감 후 별도 사이클로. C 는 전면 재디자인이 아닌 토큰
+ 공용 컴포넌트 분리로 한정. D 의 외부 브로커는 STOMP relay (RabbitMQ) 대신 Redis Pub/Sub
— 이미 의존성에 있고 인프라 추가 0. 청크 분해와 검증 기준은 `docs/plans/mvp-roadmap.md`
의 Phase 6 섹션 참조.

## D-32 (2026-05-14) — Jackson record is-getter 충돌 픽스

통합 테스트 (`RoomResyncIntegrationTest`, 전체 스위트) 실행 결과 `Card` 와
`TichuMatchState` 의 Redis 역직렬화가 실패. 원인: Jackson 의 POJO introspection 이
`isSpecial()` (boolean) 을 property "special" 의 is-getter 로 인식 → record 컴포넌트
`Special special` 과 같은 이름의 프로퍼티로 합쳐서 처리하다가, `@JsonIgnore` 가 있으면
component 까지 직렬화에서 빠지고, 없으면 canonical constructor 에 없는 boolean
프로퍼티 (`matchOver`, `active` 등) 가 직렬화돼 deserialize 시 `Unrecognized field`
실패. 픽스 두 갈래: (1) record 컴포넌트와 이름 충돌하는 `isSpecial()/isNormal()` 은
`@JsonAutoDetect(isGetterVisibility = NONE)` 를 `Card` 에 붙여 is-getter 자동 발견을
끔. (2) 충돌은 없지만 직렬화엔 빠져야 하는 헬퍼 `isMatchOver()` (`TichuMatchState`) ·
`isActive()` (`Wish`) 는 단순 `@JsonIgnore` 추가. 추가로 `GameStompControllerIntegrationTest`
의 `Map.of("suit", null, ...)` 가 NPE 를 던지던 사전 버그 (Map.of 는 null value 거부)
를 `HashMap.put` 으로 교체. 본 픽스 적용 후 단위·통합 테스트 194건 전부 그린.

## D-31 (2026-05-14) — 로컬 환경 부트스트랩: Java 25 + Gradle 9.4.1 + Colima

처음 빌드 시도에서 Spring Boot 4.0.1 + Gradle 9.x 호환성 issue 다수 발견 — 모두 본
결정에서 정리. 변경: (1) `io.spring.dependency-management` plugin 제거 → `platform(
"org.springframework.boot:spring-boot-dependencies:4.0.1")` BOM 직접 import. Spring
Boot 4 / Gradle 9 의 IBM_SEMERU 참조 제거와 호환. (2) Spring Boot 4.0 에서 autoconfigure
들이 별도 starter 로 분리됨 — `spring-boot-starter-flyway`, `spring-boot-starter-
jackson`, `spring-boot-starter-webmvc-test` 명시 추가. (3) `AutoConfigureMockMvc` 의
패키지가 `org.springframework.boot.test.autoconfigure.web.servlet` → `org.springframework.
boot.webmvc.test.autoconfigure` 로 이동 — 4개 테스트 import 갱신. (4) ObjectMapper
autoconfigure 가 호환 모듈에서 누락되어 `JacksonConfig` 에 명시적 @Bean 추가
(findAndRegisterModules 호출). (5) `TichuRoundStarter` 의 ambiguous 다중 public
생성자 → 운영 entry point 에 @Autowired 명시. (6) 사전 테스트 코드 버그 4건 수정
(AuthServiceTest User.id null, HandDetectorTest rank 1 invalid, ScoreCalculator
기대값 50→40, double victory 후 불필요한 pass 호출). 컨테이너 런타임은 Colima 채택 —
Testcontainers 와 호환을 위해 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`
환경변수 필요.

## D-30 (2026-05-14) — Phase 5e: UI polish + @dnd-kit 손패 정렬 + i18n 베이스

손패 표시는 서버 분배 순서로 렌더링되었는데, 14장이 되면 사람 눈에 찾기 어렵다.
`@dnd-kit/core` + `@dnd-kit/sortable` 로 카드를 드래그 재배열 가능하게 하고,
"랭크순 정렬" / "원본 순서로" 버튼을 손패 툴바에 둔다. 클라이언트 측 `sortOrder`
(Card key 배열) 만 store 에 보관하고 실제 손패 데이터는 서버 권위 그대로 — 정렬은
순수 표시 변환. `sortedHand` 셀렉터가 stale 키 제거 + 새 카드 append 를 자연스럽게
처리하므로 라운드/패스 전환에서 별도 reset 가 필요 없다. 클릭(선택) 과 드래그
(재배열) 는 PointerSensor 활성화 거리(8px) 로 구분. i18n 은 풀-스택 라이브러리(react-i18next)
대신 `i18n/messages.ts` 의 단일 KO 사전 객체 + `t(key)` 헬퍼로 시작 — 모든 사용자
노출 문자열을 한 곳에 모아 향후 다국어 토글이 단순 dictionary 추가만으로 가능하게.

## D-29 (2026-05-14) — Phase 5d: seq 기반 라이브 patch + /resync 폴링 축소

MVP 의 보수적 "공개 이벤트 받을 때마다 /resync" 패턴을 client store 에 idempotent
reducer 로 대체. envelope.seq 가 lastSeq 보다 작거나 같으면 dedup, 같으면 동일 이벤트
재수신이므로 무시. 정상 진행(seq == lastSeq+1) 이고 reducer 가 정의되어 있으면 부분
패치만 적용 → 대상: PLAYED, PASSED, TURN_CHANGED, TRICK_TAKEN, PLAYER_FINISHED,
TICHU_DECLARED, WISH_MADE, DRAGON_GIVEN, PLAYER_READY, PASSING_SUBMITTED. 리듀서가
없는 라이프사이클 이벤트(DEALING_PHASE_STARTED, PASSING_STARTED, CARDS_PASSED,
PLAYING_STARTED, ROUND_STARTED) 와 seq gap (>lastSeq+1) 에서는 /resync 로 권위 있는
스냅샷 재취득 후 lastSeq 동기화 — 단순성 vs 정확성 트레이드오프에서 후자 우선. 초기
mount 와 STOMP onConnect 시 /resync 는 그대로 유지 (재접속/리로드 안전망).

## D-28 (2026-05-14) — Phase 5c: 멀티 라운드 + 1000점 매치 종료

라운드 단위 `TichuState` 와 분리된 매치 상태 `TichuMatchState(cumulativeA, cumulativeB,
roundNumber, playerIds)` 를 Redis 키 `match:{roomId}:state` 에 별도 저장. 한 라운드가
`RoundEnd` 에 도달하면 `GameStompController` 가 라운드 점수를 누적 → 한 팀이 1000점
이상이고 양팀 점수가 다르면 매치 종료 (`TichuMatchCompleted` 발행 + 방 FINISHED +
DB 영속화), 아니면 즉시 `TichuRoundStarter.startRound` 로 다음 라운드 Dealing(8) 을
재생성하고 공개 이벤트 `RoundStarted(roundNumber)` 발행. `MatchResultRecorder` 는
이제 `TichuRoundCompleted` 가 아닌 `TichuMatchCompleted` 를 listen — `tichu_match_results`
에는 매치당 1행만 적재되고 라운드별 RoundScore 들은 payload_json 에 배열로 보관.
양팀이 같은 라운드에 1000점에 도달하거나 동점이면 한 라운드 더 진행. TableView 에
matchScores + roundNumber 추가, 클라이언트는 헤더에 누적 점수를 노출.

## D-27 (2026-05-14) — Phase 5b: Dealing/Passing 프리뤼드

라운드 시작을 Playing 직행에서 본 룰대로 복원: `TichuState` 에 `Dealing(phase=8|14,
ready set, reservedSecondHalf)` 를 추가하고 `Passing.submitted` 는 단순 boolean 에서
실제 패스 카드 셀렉션(`PassCardsSelection(toLeft, toPartner, toRight)`) 을 담는 맵으로
확장. 신규 액션 `Ready` 는 "선언 안 함, 다음으로 가자" 신호. Engine: Dealing(8) 에서
4명이 모두 ready 가 되면 reservedSecondHalf 를 hand 에 합쳐 Dealing(14) 로, 다시 4명
ready 면 `Passing` 으로, 4명 모두 PassCards 제출하면 동시 스왑 → `Playing` (Mahjong
보유자 리드). 상태 은닉은 그대로 — 손패 변경(추가 6장 / 패스 후 -3+3)은 클라가
`/resync` 로 갱신, 공개 이벤트는 PlayerReady / DealingPhaseStarted / PassingStarted /
PassingSubmitted / CardsPassed / PlayingStarted. Grand Tichu 는 Dealing(8) 에만, Tichu
는 Dealing(14) + Playing 첫 플레이 전까지 허용. 클라이언트 UI 는 phase 에 따라 선언
버튼 / 3-슬롯 카드 패스 피커를 노출.

## D-26 (2026-05-14) — Phase 5a: 매치 결과 영속화 + 방 FINISHED 전이

라운드가 끝나 `TichuState.RoundEnd` 로 전이되면 `GameStompController` 가
`TichuRoundCompleted` ApplicationEvent 를 발행한다. `MatchResultRecorder` 가 본
이벤트를 listener 로 받아 `tichu_match_results` (room_id, 점수 두 컬럼, payload_json
에 RoundScore 직렬화) + `tichu_match_participants` (match_id, user_id, team=A/B,
is_win) 를 단일 트랜잭션으로 기록하고, 각 유저의 `win_count`/`lose_count` 를 `UPDATE`
쿼리로 증분. 동시에 `RoomService.markFinished` 가 Redis room hash 의 status 를
`FINISHED` 로 갱신하고 `rooms:open` ZSET 에서 ZREM. JPA 엔티티는 D-02 의 컬럼 금지
규칙과 일치 (식별/연락 정보 무추가).

## D-25 (2026-05-14) — Phase 4f: 재접속 복원 + Phase 4 마감

`useStompRoom` 의 재접속 흐름은 (1) 초기 마운트 시 `/api/rooms/{id}/resync` 호출,
(2) STOMP `onConnect` 콜백마다 다시 `/resync`, (3) 공개 이벤트 수신 시도 보수적으로
`/resync` 재호출 — 세 경로 모두 동일한 권위 있는 스냅샷으로 store 를 덮어쓴다.
새로고침 / 네트워크 끊김 후 자동 재연결 / 토큰 영속 모두 같은 finalize 패턴. `seq`
는 향후 라이브 patch 도입 시 idempotent 가드 역할 예약, MVP 는 polling-on-event.
Phase 4 전체 완료 — 시연 플로우는 README 에 기록.

## D-24 (2026-05-14) — Phase 4e: 게임 테이블 UI + useStompRoom

`useStompRoom(roomId)` 훅이 `/topic/room/{id}` (공개) 와 `/user/queue/room/{id}` (본인
HandDealt/ERROR) 를 동시 구독하고 단조 증가 `seq` 로 중복 이벤트를 idempotent 폐기.
`tichuStore`(Zustand) 가 TableView + PrivateHand + 선택된 카드 상태 관리. 카드 선택은
단순 클릭 토글로 시작 — `@dnd-kit` 기반 드래그 정렬은 UX 향상 작업으로 후속 보류.
RoomPage 는 status=IN_GAME 시 GameTable 을 렌더링, 그 외에는 대기실 뷰 유지.

## D-23 (2026-05-14) — Phase 4d: 프론트엔드 스캐폴드 + Auth/GameHub/Lobby

`client/` 디렉토리 신설 (Vite + React 18 + TypeScript). 상태관리는 Zustand (인증, 로비),
서버 통신은 fetch 래퍼 + JWT Bearer (인메모리 + localStorage 영속), STOMP 는
`@stomp/stompjs` (Phase 2e 서버측과 동일 envelope). 라우팅 React Router v6 — `/login`,
`/games`, `/games/:gameId/lobby`, `/rooms/:roomId`. 페이지 컴포넌트는 단순 div + 인라인
또는 CSS 모듈 (디자인은 4e/4f 에서 보강). 새 게임 추가 시 클라 측 변경은 `pages/Game*`
+ `features/{game}/` 만 — 카탈로그/로비 코드는 그대로.

## D-22 (2026-05-13) — Phase 4c: 재동기화 + 상태 은닉 매퍼

`TichuStateMapper` 가 마스터 `TichuState` 를 공개 `TableView`(손패 장수만, 카드 절대
미포함) 와 본인 한정 `PrivateHand`(실제 카드) 로 분리 — State Hiding 의 직렬화 단계
마지막 방어선. `RoomController` 에 `GET /api/rooms/{roomId}/resync` 추가, 응답은
`{roomId, phase, eventSeq, tableView, privateHand}`. 참가자 아니면 `NOT_IN_ROOM`(409),
게임 미시작이면 `RESYNC_NOT_AVAILABLE`(404). `TichuGameStateStore.currentSeq` 가 클라
재접속 후 더 작은 seq 이벤트를 idempotent 하게 건너뛸 수 있게 한다.

## D-21 (2026-05-13) — Phase 4b: STOMP 게임 액션 라우팅

`GameStompController` 가 `/app/room/{roomId}/action` 으로 들어온 `TichuAction` 을
처리한다. 흐름: `RoomActionLock(setIfAbsent, EX 2s)` 획득 → 상태 로드 → `TichuEngine.apply`
→ 새 상태 저장 → `GameEventBroadcaster` 가 envelope `{eventId, seq(=room:{id}:seq INCR),
ts, type, payload}` 로 감싸 공개 이벤트는 `/topic/room/{id}`, `HandDealt` 같은 비공개
이벤트는 `/user/{userId}/queue/room/{id}` 로 분기. 검증 실패/락 실패는 본인 큐로 ERROR
회신 — 타인 상태 비변경 보증. STOMP user destination 라우팅 안정화를 위해
`AuthPrincipal.getName()` 이 username 대신 userId 문자열을 반환하도록 변경.

## D-20 (2026-05-13) — Phase 4a: 게임 lifecycle + 상태 영속화

방이 capacity 도달 시 (`room_join.lua` 가 status=IN_GAME 전이) RoomService 가
`GameStartingEvent(roomId, gameType, playerIds)` 를 발행한다. 각 게임 도메인은 본 이벤트의
listener(`TichuRoundStarter`) 로 카드 셔플 · 분배 · 초기 `TichuState.Playing` 구성 ·
Redis(`room:{id}:state`) 저장을 수행 — 모듈 경계 유지(lobby 가 game 을 직접 부르지 않음).
`TichuGameStateStore` 는 `StringRedisTemplate` + Jackson 으로 sealed `TichuState` 의 JSON
round-trip 을 처리, sealed 계층은 `@JsonTypeInfo(NAME, "@phase"/"@action"/"@event")` 변별.
MVP 단순화로 Dealing/Passing 프리뤼드는 스킵, 분배 직후 Mahjong 보유자가 첫 리드인
Playing 상태로 진입.

## D-19 (2026-05-13) — Phase 3f: TichuEngine 통합

`TichuEvent` sealed interface 안에 nested record 로 이벤트 (Played, Passed,
TurnChanged, TrickTaken, WishMade, TichuDeclared, DragonGiven, PlayerFinished,
RoundEnded) 정의. `TichuEngine.apply(state, seat, action) → Result(newState, events)`
가 단일 진입점. 패턴 매칭으로 액션을 분기하고, Phoenix 단독 SINGLE 은 엔진이
currentTop.rank 로 정규화해서 후속 비교가 정수 산수로 일관됨. 트릭 폐쇄는
"advanceTurn 이 currentTopSeat 로 돌아오면" 이라는 단일 신호. 라운드 종료는 더블
빅토리 또는 3명 완주 두 경로, ScoreCalculator 호출 후 `TichuState.RoundEnd` 로 전이.
`TichuGameDefinition.newEngine` 이 실제 엔진을 반환하도록 Phase 2c 의 stub 을 졸업.

## D-18 (2026-05-13) — Phase 3e: 점수 계산 + 라운드 종료

`scoring/CardPoints` 에 카드 점수와 보너스 상수를 모아두고 `ScoreCalculator` 가
`List<PlayerState>` 만 받아 `RoundScore(teamA, teamB, firstFinisher, doubleVictory)`
를 반환한다. 두 가지 종료 경로: (1) 더블 빅토리 — 한 팀의 두 명이 1·2등을 점유하면
즉시 +200, 정상 트릭 합산 생략, 티츄 보너스만 추가. (2) 정상 종료 — 3명이 완주하면
패자(4등)의 tricksWon 은 1등 팀에, 패자 손에 남은 카드 점수는 상대 팀에 가산. 모든
경우에 Tichu(±100)/Grand Tichu(±200) 선언은 별도 보너스로 합산.

## D-17 (2026-05-13) — Phase 3d: 상태 · 액션 · 검증

`TichuAction` sealed interface 안에 7개 record (DeclareGrandTichu, DeclareTichu,
PassCards, PlayCard, PassTrick, MakeWish, GiveDragonTrick) 를 nested 로 둬서 파일
폭증을 막는다 (`GameAction` 의 게임별 sealed 패턴). `TichuState` 도 sealed interface
안에 Passing/Playing/RoundEnd 3 상태 record. `Team` enum (A/B), `Seat` 헬퍼는
별도 객체 없이 int 0..3 + `TurnManager` 의 정적 메서드로 표현. `ActionValidator` 는
`pattern switch` 분기로 각 액션의 차례·보유·족보·소원·Dog/Phoenix/Dragon 제약을
검증 — 실패 시 `RejectionReason` enum 을 담은 `TichuActionRejectedException`.
와일드 카드 wish 강제는 conservative 방식 (보유 시 미포함 → reject), 완벽한 "legal
play 존재" 탐색은 Phase 3f 엔진에서 다듬는다.

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
