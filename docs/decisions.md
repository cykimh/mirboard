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

## D-99 (2026-07-30) — 방 인원 가변: `POST /api/rooms` 의 `capacity` 선택 필드 (M5/T7 S2)

D-97 이 스컬킹을 2~8인으로 확정하면서 `capacity = def.maxPlayers()` 고정이 병목이 됐다 —
그대로면 스컬킹 방은 항상 8인이 되어 4인 게임을 만들 수 없다. `POST /api/rooms` 에
`capacity` **선택** 필드를 추가하고(미지정 시 `def.maxPlayers()` — 현행 호환),
`def.minPlayers() <= capacity <= def.maxPlayers()` 를 벗어나면 `INVALID_CAPACITY` 로 거절한다.
`fillWithBots` 의 좌석 계산도 `maxPlayers` 대신 확정된 capacity 를 따른다.

티츄는 `min=max=4` 라 요청 본문·UI·동작이 모두 **무변경**이다 — 기존 방 생성 IT 전량 그린이
그 증거다. 클라 방 만들기 모달의 인원 선택은 `minPlayers !== maxPlayers` 인 게임에서만
노출하므로 티츄 모달은 그대로다. 계약 변경이라 서버 DTO + 클라 미러 + `docs/api.md` 를
한 커밋으로 묶는다(parallel-tracks 직렬화 4).

**순서**: 계획(`docs/plans/multi-game-sessions.md`)은 S2 의 선행을 S1(D-98, 포트 추출)로
적었으나, S2 가 만지는 파일(`RoomService`·`RoomController`·클라 모달)과 S1 이 만지는 파일
(`GameEngine`·`GameStompController`·스케줄러)의 교집합이 없고 `game-port.md` §3 이 포트
표면을 참조하지 않아 먼저 진행했다. D-98 은 S1 용으로 예약 상태를 유지한다.

## D-97 (2026-07-30) — GameEngine 포트 설계 (M5/T7 1단계, 코드 변경 0)

멀티게임의 선행 설계. `docs/game-port.md` 가 산출물이고 **코드는 건드리지 않는다** —
포트를 잘못 잡으면 두 게임을 다 고쳐야 하므로 표면을 먼저 종이에서 확정한다.

**전제 정정**: D-06/D-11 이 약속한 "새 게임 = Bean 추가만"은 **카탈로그까지만 사실**이다.
`GameEngine`·`GameAction`·`GameEvent` 는 메서드 0개 빈 마커이고 `newEngine()` 은 호출부가
0건이며, infra 10파일이 Tichu 를 123회 직접 참조한다. CLAUDE.md 의 해당 문구는 포트
추출(2단계) 완료 시점에 참이 된다 — 그 전까지는 문서가 앞서 있는 상태임을 명시한다.

**스컬킹 인원 2~8 확정**(BGG 추천 4~6). 이 결정이 곧 **인원 가변 지원을 필수로 만든다** —
현재 `RoomService.createRoom` 이 `capacity = def.maxPlayers()` 로 고정하고 있어, 2~8 게임은
방 생성 시 인원을 고르지 않으면 항상 8인 방이 된다. 계약 변경(REST + 클라 + docs)이
포트 추출과 별개 단계로 들어간다.

**포트 표면은 티츄 구현에서 역산한다.** 지금 인게임이 실제로 요구하는 것은 6가지다:
① 액션 적용(`apply`) ② 상태 직렬화 ③ 공개/비공개 뷰 ④ 단계 이름 ⑤ 라운드·매치 진행 판정
⑥ 봇·타임아웃용 합법 액션. 이 중 **③의 비공개 뷰는 요트에 존재하지 않으므로 Optional**
이어야 하고, ⑤는 게임마다 종료 조건이 달라(티츄=점수, 스컬킹=10라운드 고정) 엔진이
"매치가 끝났는가"를 스스로 답해야 한다.

**팀 개념을 포트에서 뺀다.** 티츄는 2:2 고정이지만 스컬킹·요트는 개인전이다. 점수를
`Map<Team,Integer>` 가 아니라 **좌석별**로 다루고, 팀 합산은 게임 내부 관심사로 내린다.

## D-96 (2026-07-30) — 수평 확장: in-memory 스케줄러/프레즌스 → Redis (M3, T6) · **D-03 전제 번복**

프로젝트 서사("분산 전환을 실제로 증명한 서버-권위적 실시간 게임 서버")의 핵심. 실측 결과
단일 인스턴스 전제는 **정확히 세 곳**에 있고 전부 in-memory 맵이다.

| 대상 | 보유 상태 | 깨지는 방식(2인스턴스) |
| --- | --- | --- |
| `WsSessionRegistry` | `Map<sessionId, (userId, roomId)>` | A 인스턴스에 붙은 세션을 B 가 모른다 → `hasLiveSession` 이 거짓 음성 → **재접속했는데 탈주 처리** |
| `TurnTimeoutScheduler` | `generations`(room→AtomicLong) + `futures` | 두 인스턴스가 각자 타이머를 걸어 **중복 자동행동**, generation 가드는 프로세스 안에서만 유효 |
| `DesertionGraceScheduler` | `futures`((room,user)→future) | 유예를 건 인스턴스가 죽으면 **탈주가 영원히 확정 안 됨** |

**D-03 번복 범위**: Lua 원자화 자체는 유지하며 오히려 더 중요해진다. 번복하는 것은 그
결정에 붙은 **전제** — "단일 인스턴스 Redis 원자성으로 충분하므로 애플리케이션 상태를
in-memory 에 둬도 된다" — 쪽이다(D-75·Phase 13D 주석이 이 전제를 명시적으로 인용하고 있다).

**타이머 모델은 ZSET 폴링 + 락으로 한다.** 리더 선출은 쓰지 않는다 — 리더가 죽는 순간
모든 타이머가 멈추고, 선출 자체가 새로운 장애 모드다. 대신 만료 시각을 Redis ZSET
(`deadlines:{kind}`, score=epochMs)에 두고 **모든 인스턴스가 주기적으로 폴링**해 Lua 로
"만료분 원자 pop" 한 뒤 `RoomActionLock` 아래서 처리한다. 인스턴스가 죽어도 다른
인스턴스가 같은 ZSET 을 보므로 **자동 인계**되고, 중복 실행은 pop 원자성 + 기존 generation
가드가 막는다. 폴링 주기 1s 는 턴 제한(30~90s)·탈주 유예(120s) 정밀도에 충분하다.

**프레즌스는 세션 카운터로 한다.** `presence:room:{roomId}` HASH(userId→세션 수)에
SUBSCRIBE 시 INCR / DISCONNECT 시 DECR(0 이면 필드 삭제). 한 유저가 탭 두 개를 열어도
하나만 닫히면 여전히 접속 중이어야 하므로 boolean 이 아니라 카운터다. TTL 로 고아 정리.

**M0 이연분 포함**: `TurnTimeoutScheduler` 의 in-memory 누수(방이 끝나도 generations/
futures 엔트리가 남는 경로) — Redis 이전으로 자연 해소되며 키에 TTL 을 건다.

**검증은 2-인스턴스 통합 테스트로 한다.** "단일 인스턴스에서 안 깨졌다"는 증명이 아니다.
`TwoInstanceHandoffIT` 가 같은 Redis/Postgres 를 물린 독립 Spring 컨텍스트 2개를 띄워
① 한쪽이 건 데드라인이 실행됨 ② **A 를 종료해도 B 가 인계**(구 구현이 탈주를 영영 확정
못 하던 형태) ③ 두 인스턴스 동시 폴링에도 중복 실행 0 ④ A 의 프레즌스를 B 가 조회 —
4건 모두 통과.

**트레이드오프 — 폴링 지연**: 정확한 시각에 깨는 `ScheduledExecutorService` 와 달리
타이머마다 **최대 한 폴링 주기의 지연**이 붙는다. 운영 값(턴 30~90s, 유예 120s)에는
무의미하지만, 실측에서 `TurnTimeoutSchedulerIT`(turnSeconds=1)가 지연 증폭으로 120초
안에 매치를 못 끝내 **실제로 실패했다**. 기본 주기를 1s→**250ms** 로 낮추고(인스턴스·kind
당 초당 4회 — 무시할 부하), 초 단위 타이머를 쓰는 그 테스트만 50ms 로 더 낮췄다.
정확도가 필요한 타이머가 생기면 이 특성을 먼저 고려할 것.

**검증 결과**: 서버 391건 그린(기존 383 + 신규 8: `DistributedInfraIT` 7 · 2인스턴스 4 중
일부 중복 제외). `WsSessionRegistryTest` 는 삭제 — 탭 카운팅 포함 의미를 실제 Redis 를 쓰는
`DistributedInfraIT` 가 더 강하게 덮는다.

## D-94 (2026-07-30) — 클라 기술부채: styles.css 분할 1차 + 사문화 제거 (T5)

`parallel-tracks.md` §1 이 지목한 병목 해소. 목적은 가독성이 아니라 **두 번째 클라 트랙을
가능하게 만드는 것** — 현재 `styles.css`(1623줄)와 `tichuStore.ts`(539줄) 때문에 클라는
동시 1트랙 제약이 있다.

**분할 범위를 절반으로 한정한다.** 4축 실측 결과 15그룹 중 **10개는 파일 안에서 이미 연속
구간**이라, 잘라내 원위치에 `@import` 로 되끼우면 최종 concat 이 **바이트 동일**하다 —
캐스케이드 위험이 논증이 아니라 동어반복으로 0이 된다. 나머지 5그룹(arena/seat, my-hand,
match-ended, header, shell)은 조각이 파일 전역에 흩어져 있어 병합하면 전역 순서가 반드시
바뀐다. 이 5그룹은 **2차로 이월**하고, 근거(postcss 캐스케이드 승자 표)가 안 만들어지면
분할을 포기하고 섹션 주석으로 남기는 것을 정당한 결론으로 인정한다.

**@media 는 분산하지 않는다.** 폭 미디어 4종(720/768/480/440)이 컴포넌트 경계를 가로지르고
순서 의존 사다리가 8쌍인데, 그중 `.table-center-trick { max-width }` 는 480(44%)→440(46%)로
**비단조**라 순서가 바뀌면 값 자체가 달라진다(D-88 이 정확히 이 실패 모드였다). 전량
`responsive.css` 하나로 모아 **항상 마지막** import. 예외는 `.room-chat-*` 전용 미디어 2개.

**분할 전에 사문화 규칙을 지운다.** 특히 `.table-center-trick .cs-rank/.cs-suit` 는
`.card-chip .cs-rank` 와 같은 엘리먼트·같은 특이도(0,2,0)인데 파일 뒤라 이미 죽어 있다 —
가장 위험한 hazard 를 **렌더 1픽셀 변화 없이** 없앤다. (특이도를 올려 되살리는 선택지도
있으나 그건 시각 변경이라 무변경 규율 위반.)

**클래스명은 동결한다.** `GameTable.test`·`SeatCardStack.test`·`CardChip.test` 11건이
클래스명 문자열에 의존하며, D-87 규율상 리팩토링으로 테스트가 빨개지면 테스트가 아니라
변경을 되돌린다.

**i18n 대량 이관은 이번 범위 밖.** 하드코딩 252곳이 26파일에 걸쳐 있어 T5 의 목적(트랙
분리)에 기여하기는커녕 최대 cross-cut 이 된다. 각 트랙이 자기 화면을 건드릴 때 곁다리로
처리하는 게 맞다.

**발견했으나 고치지 않은 결함**(동작 변경이라 별건): ① `applySnapshot` 이 `chipDeltas` 를
정리하지 않음 ② `sortOrder` 라운드 리셋이 주석과 달리 구현에 없어 2라운드 손패 정렬이 섞임
③ 좌석 상태 우선순위가 소스 순서에만 의존(끊김+준비 = 초록).

## D-93 (2026-07-30) — 채팅 신고 적재/조회 (D-86 후속, T4)

D-86 이 후속으로 남긴 조각. 채팅은 인메모리 broadcast 라 **서버에 원문이 없어서**, 신고가
들어와도 무엇이 신고됐는지 알 수 없는 상태였다.

**원문 확보는 클라 제출이 아니라 서버 보관으로 한다.** 클라가 신고와 함께 메시지 본문을
보내면 "상대가 이런 말을 했다"를 위조할 수 있어 무고가 가능하다(Server-Authoritative
위반). 대신 서버가 broadcast 시 최근 메시지를 **Redis 링버퍼**(`chatlog:{scope}`, 최근
100개, TTL 2h)에 짧게 보관하고, 신고는 **`eventId` 만 지목**한다 — 원문·작성자는 서버가
자기 보관분에서 확정한다. 클라는 "어느 메시지인지"만 말할 수 있고 "무슨 내용인지"는
주장할 수 없다. envelope 의 `eventId` 가 이미 있고 클라 `roomChatStore` 도 보관 중이라
프로토콜 변경이 필요 없다.

**개인정보 범위**: 상시 채팅 로그를 영속화하지 않는다 — Redis 보관은 휘발(TTL 2h)이고,
**신고된 메시지만** `chat_reports`(Flyway V9)로 승격된다. `users` 화이트리스트 불변
(D-02) — 별도 테이블 선례는 D-80(아바타)·D-86(admin_roles). 스냅샷 컬럼은 신고 판단에
필요한 최소(작성자·신고자 userId, 메시지 본문, 발생 시각, 방 scope)만 두고 IP·세션 등
식별 정보는 담지 않는다. 보존 기간 자동 정리는 미구현(후속) — 런북에 수동 정리 근거를 남긴다.

**남용 방지**: `UNIQUE(event_id, reporter_user_id)` 로 같은 사람의 중복 신고를 막고,
자기 메시지 신고는 거절한다. 신고 자체도 D-90 레이트리밋의 `expensive-write` 버킷을 탄다.

**범위**: 적재 + 어드민 조회까지. 처리 상태(resolve/dismiss)는 넣지 않는다 — 계획서
scope 가 "적재/조회"이고, 상태 머신을 지금 만들면 검증 없이 표면만 넓어진다(후속).

## D-92 (2026-07-30) — 백업 런북 + k6 부하 시나리오 (M2-C5, T3)

운영 하드닝의 마지막 조각. **서버 자바 소스 무변경** — `scripts/`·`docs/runbooks/` 만
추가한다. 핵심 판단 두 가지. **(1) 백업 대상은 Postgres 뿐이다.** Redis 는 설계상
휘발(방/게임 상태·칩·세션·레이트리밋 카운터 전부 TTL 6h 이하)이고 D-82 가 칩을 계정에
두지 않기로 한 이상 "복구해야 할 Redis 데이터"는 존재하지 않는다. 런북은 백업 절차보다
**무엇이 복구 불가인지**를 먼저 못 박는다 — 진행 중 매치는 소실되며 그게 정상 동작이다.
`--appendonly yes` 는 재시작 생존용이지 백업이 아니다. **(2) k6 는 REST 만 친다.**
STOMP 부하는 k6 확장(xk6-websockets) 빌드가 필요해 "설치 한 줄"이 깨지는데, 서버에는
이미 `BotMatchSimulationIT`(`check.sh bot-stress N`)라는 인게임 부하 수단이 있다 —
둘을 합쳐 "REST 는 k6, 인게임은 봇 시뮬"로 역할을 나눈다. 임계값은 추측하지 않고 실제
측정치에서 역산해 회귀 가드로만 쓴다. k6 미설치 환경에서는 Docker 이미지로 폴백한다.
**D-90 후속 정정**: `docs/redis-keys.md` 의 `ratelimit:auth:ip:{ip}` 를 실제 키 형식
`ratelimit:{bucket}:{subject}` 로 수정(T0 이 stomp-protocol/api 만 훑어 누락됐던 드리프트).

## D-90 (2026-07-30) — 전역/STOMP 레이트리밋 완성 (M2-C1, T1)

D-84 가 인증 2개 엔드포인트에만 걸어둔 레이트리밋을 전역 HTTP + STOMP 로 일반화한다.
**알고리즘은 고정 윈도 유지** — D-84 주석의 "토큰버킷" 예고는 철회한다. 목표가 정밀한
쉐이핑이 아니라 스팸 차단이고 한도를 관대하게 잡으므로 경계 2배 버스트는 무해하며,
이미 동작하는 `rate_limit_fixed_window.lua` 를 그대로 재사용한다.
**키는 인증되면 `u:{userId}`, 아니면 `ip:{addr}`** — 지인/카페 Wi-Fi·모바일 캐리어 NAT
뒤에서 공인 IP 가 겹쳐도 서로의 할당량을 깎지 않게. 계정 다중생성 우회는 가입 IP 리밋이
이미 막는다. **구조**: 범용 `RateLimiter`(정책 카탈로그 + Lua) 위에 HTTP 필터와 STOMP
인터셉터 2개 어댑터. 각 어댑터는 선언적 라우팅 표 + **기본 버킷 fallback** 을 두어 새
엔드포인트가 조용히 무보호로 태어나는 route-drift 를 차단한다(D-61 과 같은 취지).
**게임 액션 경로도 포함하되 관대한 한도**(정상 연타·봇매치가 절대 안 걸리는 수준) —
초과 시 본인 큐 `ERROR(RATE_LIMITED)` 로 기존 `sendErrorTo` 패턴 재사용. 봇
(`BotScheduler`)·턴 타임아웃(`TurnTimeoutScheduler`)은 STOMP 를 경유하지 않고
`engine.apply` 를 직접 호출하므로 영향 없음(실측 확인). `AuthRateLimiter`/
`AuthRateLimitProperties` 는 신규 추상화로 흡수해 제거하고 환경변수 이름은 보존한다.
전부 Redis 휘발 — users 스키마 비침범(D-02 유지). Redis 장애 시 **fail-open**(레이트리밋
때문에 게임이 멈추는 편이 훨씬 나쁘다 — 로그인 brute-force 는 `LoginAttemptService` 가 별도 담당).
테스트는 `mirboard.ratelimit.enabled=false` 가 전역 기본이고 레이트리밋 검증 테스트만 켠다
(D-84 는 `auth.limit` 을 크게 잡았는데 버킷 카탈로그로 옮기며 그 키가 무의미해져 IT 13건이
429 로 깨졌다 — 개별 값이 아니라 기능 스위치를 써야 새 버킷이 생겨도 안 깨진다).
검증: 서버 377건 그린, 라우팅 단위 13건 + HTTP IT 2건 + STOMP IT 2건 신규. 사용자 간 할당량
격리는 같은 IP 에서 두 계정으로 HTTP·STOMP 양쪽 회귀 고정. 게임 무영향은 `BotMatchSimulationIT`
풀매치 + 라이브 서버 딜링→패스→플레이 실주행으로 확인. **후속**: Micrometer 카운터는
`MirboardMetrics`(T2 소유 파일)라 이번 트랙에서 건드리지 않고 M2-C3 에 위임.

## D-89 (2026-07-30) — 계약 문서 기준선 정정 (T0, 코드 변경 0)

`CLAUDE.md` 가 `docs/*.md` 를 계약 정본으로 못 박았으나 실제로는 드리프트가 누적돼,
병렬 트랙을 띄우면 각 트랙이 서로 다른(둘 다 틀린) 정본을 갖게 되는 상태였다
(`docs/plans/parallel-tracks.md` T0). 컨트롤러·`TichuEvent`·각 Publisher·클라
`useStompRoom`/`tichuStore` 를 전수 대조해 정정했다. **STOMP**: 문서에만 있던 이벤트
6종(`GAME_STARTED` `HAND_DEALT_COUNT` `WISH_FULFILLED` `BOMB_USED` `GAME_ENDED`
`HAND_DEALT_FULL`) 제거, 코드에만 있던 5종(`REACTION` `ROOM_META_UPDATED`
`ROOM_DESTROYED` `PLAYER_FINISHED` `CARDS_RECEIVED`) 추가, payload 식별자를
`userId`→**seat** 로 통일, 누락 토픽 2종(`/meta` `/reaction`) 추가, 판별자
(`@action`)·"클라→서버는 envelope 미사용"·"seq 는 게임 이벤트에만" 명문화,
`RESYNC` 가 WS 이벤트가 아니라 REST 전용임을 반영, 탈주 유예 30s→**120s**(D-79) 정정.
**REST**: `Room` 응답을 record 전체(18필드)로 교체, `resync` 응답을 실제
`TableView`/`PrivateHand` 형태로 교체(관전자 `privateHand: null`·`disconnectedSeats`·
`chips` 포함), 미문서 엔드포인트 4종(`spectate` POST/DELETE, `users/names`, 아바타
3종) 추가, **구현 없는 `GET /api/me/stats` 섹션 삭제**. 검증: 엔드포인트 24종·이벤트
28종 양방향 기계 대조 0건 불일치.

## D-88 (2026-07-30) — 게임판 UI 결함 4건 보정 (라이브 실측 후속, 클라 전용)

D-87 병합 직후 라이브 서버 + 내장 브라우저 실측(401px)에서 확인된 결함 4건을 보정.
(1) **손패 겹침 wrap 폴백** — 한 줄 겹침의 카드당 가시폭이 하한(`MIN_VISIBLE_STEP`
30px) 미만이면 `useHandOverlap` 이 `data-hand-wrap` 을 세워 줄바꿈으로 펼친다. 카드
축소로는 해결 불가(step≈(W−cardW)/(n−1) 이라 폭 지배적)라 구조적 폴백을 택했다.
(2) **480px 미디어 블록 사문화 수정** — 768px 블록이 파일상 뒤에 있어 캐스케이드로
480 블록을 덮던 순서 버그. 블록을 768 뒤로 이동(+주석으로 순서 의존 명시), 겸사
좌우 좌석 카드 팬 축소·좌석 바깥 밀착으로 중앙 트릭 패널과의 면적 겹침 제거.
(3) **터치 타겟 44px 하한** — 헤더 토글·액션 바·이모지 팔레트에 `min-height/width`
보정(`pointer: coarse` 또는 ≤768px 한정, 데스크톱 마우스 무영향).
(4) **비활성 '내기' 어포던스 역전 수정** — primary(밝음)+opacity 0.5 가 활성
'패스'(secondary)보다 밝게 남던 것을 비활성 시 secondary 표면으로 강등.
스키마·프로토콜·서버 무변경. 검증: vitest 139(신규 4)·tsc·라이브 DOM 재실측
(겹침 0·44px 미만 0건·wrap 3줄 동작).

## D-87 (2026-07-29) — GameTable 컴포넌트 분해 (트랙 외 기술부채)

M1 A6/A7 게임판 UX 폴리시가 연속으로 쌓이며 `GameTable.tsx` 의 단일 컴포넌트가
915줄(파일 1030줄)까지 커졌고, 직접 테스트가 없어 다음 클라 UX 변경 시 회귀 위험이
가장 높은 지점이 되었다. **동작·시각 변경 없이** 프레젠테이션 4개(`GameTableHeader`·
`TableArena`·`MyHandPanel`·`MatchEndedPanel`)와 훅 3개(`useGameTableModel`·
`useGameTableEffects`·`useGameActions`), 순수 함수 1개(`gameTableSelection`)로
분해하고, 추출 **전에** 특성화 테스트를 먼저 붙여 안전망을 확보한다. 스타일
클래스명·STOMP 프로토콜·`tichuStore` 구조·서버 계약은 불변이며 성능
최적화(memo/useCallback)는 별건. 상용화 트랙(M0~M5) 밖의 기술부채라 마일스톤
번호를 붙이지 않는다. 상세는 `docs/plans/gametable-refactor.md`.

**계획 대비 두 가지 보정**: (1) 컴포넌트 4개를 뺀 뒤에도 GameTable 이 405줄이라
스토어 구독·파생값을 담는 세 번째 훅 `useGameTableModel` 을 추가했다(계획서엔
없던 파일). (2) 그래도 §6 의 "200줄 이하"는 못 맞춘다 — 최종 278줄이고, 남은 건
전부 조립 코드(import 22 / props 인터페이스 23 / 훅 플러밍 45 / JSX prop 나열 161)라
자식이 스토어를 직접 구독해야만 더 줄어든다. 그건 "프레젠테이션 컴포넌트" 설계와
단독 렌더 테스트 용이성을 깨므로 하지 않았고, 기준을 "조립 루트만 남을 것
(useEffect/useRef/핸들러/마크업 0)"으로 보정했다. 검증은 육안 대신 리팩토링 전후
커밋에서 17개 시나리오의 렌더 DOM 을 덤프해 비교했고, `class` 속성 내부 공백
개수(HTML 이 토큰 구분자로만 취급)를 정규화하면 **바이트 단위 완전 일치**했다.

## D-86 (2026-06-21) — 어드민/모더레이션: 역할 별도 테이블 (M2/C4)

운영 신뢰성용 최소 어드민 기능. **규칙#3(D-02) 준수**: 권한을 `users` 에 두지 않고 신규
`admin_roles`(Flyway V8: `user_id` PK FK→users, `granted_at`)로 분리한다. 권한 판정은 매 요청
`admin_roles` 조회(소규모라 비용 무시·즉시 반영). `/api/admin/**` 는 어드민만(비어드민 403
`NOT_ADMIN`). 어드민 부여는 DB insert(운영 스크립트)로만 — 자가 승격 엔드포인트 없음.
**슬라이스 1(본 항목)**: 매치 강제 종료 — `RoomService.adminAbortGame`(host 검증 없이
IN_GAME→FINISHED, 기존 host용 `abortGame` 과 분리). 컨트롤러는 `AdminAuthorization.requireAdmin`
위임만(규칙#4 — 룰 로직 없음). **후속 슬라이스**: 유저 정지(Redis `suspend:user:{id}` TTL,
로그인/CONNECT 차단 — users 비침범), 채팅 금칙어 마스킹/신고. 감사는 구조화 로그(MDC)로 시작,
전용 테이블은 후속. Flyway-only(규칙#6).

## D-85 (2026-06-21) — 셀프서비스 비밀번호 변경 (PUT /api/me/password, M1/A4)

프로필/설정에서 본인 비밀번호를 바꿀 수 있게 한다. 신규 `PUT /api/me/password`(인증 필요)는
현재 비밀번호를 재검증한 뒤 새 비밀번호를 `PasswordPolicy`(8~64자)로 검증하고 BCrypt 로
재해시해 `users.password_hash` 만 갱신한다 — **스키마/마이그레이션 변경 없음**(기존 컬럼 재사용,
D-02 화이트리스트 불변). 현재 비번 불일치는 401(`BAD_CREDENTIALS`), 정책 위반은 400(`INVALID_INPUT`).
**토큰 정책(사용자 결정)**: 변경 후 기존 발급 JWT 를 그대로 **유지**(최대 12h)한다 — 단순성 우선,
소규모/포트폴리오 범위. 즉시 무효화(토큰 버전/블랙리스트)는 트랙 C(M2) 인증 하드닝에서 재검토.
클라는 `/profile` 라우트로 전적·아바타·비밀번호 변경을 통합한다.

## D-84 (2026-06-21) — 로그인 brute-force 잠금 + 인증 레이트리밋 (Redis 전용, M0)

상용화(포트폴리오) M0 보안 하드닝. 로그인 무제한 시도를 막는다. 실패 카운터
`login:fail:{username}`(INCR, 윈도 TTL)가 임계(기본 5회) 초과 시 `lock:login:{username}`
(짧은 TTL, 기본 15분) 으로 잠가 `ACCOUNT_LOCKED`/423 반환. 인증 엔드포인트(`/api/auth/login`,
`/register`)는 IP 고정 윈도 카운터(Lua 원자 `INCR`+`EXPIRE`, `room_*.lua` 패턴 재사용)로
레이트리밋해 초과 시 `TOO_MANY_REQUESTS`/429 (토큰버킷·전역 추상화는 M2). **규칙#3(D-02) 준수**: `users` 테이블에 `failed_attempt`/`locked_until`
컬럼을 추가하지 **않고** 전부 Redis(휘발)로 둔다(스키마/마이그레이션 무변경). **DoS 완화**:
username 단독 잠금은 타인 계정 잠금 공격이 가능하므로 잠금 TTL 을 짧게 두고 IP 레이트리밋을
병행, 영구 잠금은 두지 않는다. 성공 로그인 시 두 키 삭제. 범위: 인증 표면만 — STOMP SEND/전역
HTTP 필터로의 확장은 M2(C1 완성). in-memory degrade 추상화(`RateLimiter` 인터페이스 2구현)도
M2 로 미룬다(Redis 는 이미 필수 의존이라 M0 은 Redis 직접 사용). 영향: `docs/redis-keys.md`
(신규 키 3종), `docs/api.md`(429/423), `application.yml`(`mirboard.ratelimit.*` 기본값).

## D-83 (2026-06-21) — CORS origin 화이트리스트 + 보안 헤더 (M0)

상용화(포트폴리오) M0 보안 하드닝. WebSocket/HTTP origin 전면 개방
(`setAllowedOriginPatterns("*")`, `WebSocketConfig` 2곳)을 환경별 화이트리스트
(`mirboard.security.allowed-origins`, dev=localhost:5173/8080, prod=실도메인 env 주입)로 고정한다.
`SecurityConfig` 에 `CorsConfigurationSource` 빈 + 보안 헤더: `X-Content-Type-Options=nosniff`,
`X-Frame-Options=DENY`, `Referrer-Policy=strict-origin-when-cross-origin`, HSTS(스프링 기본 동작상
HTTPS 요청에서만 송출돼 dev http 에 무해, 프로필 분기 불필요). **CSP 는 보류** — 정적 자산
(`/cards`, `/avatars`, SPA)이 `/api` 밖(규칙#8)이라 잘못 좁히면 데모가 백지화될 위험이 커
report-only 도입을 M2(C 트랙)로 미룬다. **불변**: 비-API default-permit(규칙#8)·`/ws` 핸드셰이크
permitAll·STOMP CONNECT 인증(ChannelInterceptor)은 그대로. 영향: `docs/architecture.md` 보안 섹션,
`application.yml`/`application-prod.yml`(`mirboard.security.allowed-origins` 키).

## D-82 (2026-06-20) — 내기 칩: 계정 지갑 → 방 단위 테이블 칩 (D-81 보정)

D-81 의 계정 영속 지갑(`users.chip_balance`)을 **방 단위 테이블 칩**으로 바꾼다(사용자 요청).
칩은 계정에 누적되지 않고 방 안에서만 존재·이동한다: 방 게임 시작 시 전원 동일 칩
(`STARTING_STACK`, 기본 1000)으로 시작, 매치 종료마다 판돈이 승팀↔패팀으로 이동(제로섬, 패자
보유분 한도 올인), '한 판 더'(리매치)로 같은 4명이 같은 테이블에서 계속 플레이하며 칩 누적,
새 매치 시작 시 판돈 미만 보유자는 무료 재바이인, 방을 나가면 칩 소멸. **번복**:
`users.chip_balance`(V7 DROP)·무료충전(`/api/me/chips/topup`)·ready-시-잔액검증·`/me`/ranking/
stats 의 chipBalance 전부 제거 → 화이트리스트(D-02)에서 `chip_balance` 제외(계정엔 게임 재화
컬럼도 두지 않음). **유지**: 판돈(stake) 필드·allowlist `{0,10,50,100,500}`·판돈 방 봇 금지·봇
매치 정산 제외. 칩 스택은 `room:{id}:chips`(Redis HASH, 방 소멸 시 정리)에 두고 `CHIPS_SETTLED`
공개 이벤트로 브로드캐스트(테이블 공개 정보). 정산은 `MatchResultRecorder`(DB)가 아니라 신규
`RoomChipService`(Redis)가 담당.

## D-81 (2026-06-20) — 가상 칩 내기 모드 (현금 배제, D-02 보정)

판당 판돈을 거는 내기 모드를 **가상 칩(미르 칩)** 으로만 제공한다 — 현금 입출금·환전
없음. 현금 베팅은 ① 국내 온라인 현금 베팅 운영이 형법상 도박개장죄 소지, ② PG사 베팅
가맹 거부로 결제 연동 불가, ③ 실명확인(KYC)/AML 이 D-02 개인정보 최소화와 충돌해 배제
(사용자 동의). **D-02 보정**: `users.chip_balance`(BIGINT, 기본 1000, V6)를 화이트리스트에
추가 — rating/desert_count 와 같은 게임 재화 derived 값이라 식별/연락 정보가 아니고 신규
식별 컬럼은 없음(원칙 불변). 규칙: 판돈 고정 allowlist `{0,10,50,100,500}`(0=내기 없음),
**판돈 방은 봇 금지**(봇=무한 잔액→파밍 방지, 봇 포함 매치는 ELO처럼 칩 정산 제외), 정산은
**매치 종료 시 1회**(선차감 없음 — 로비 1인 1방 불변식 + ready 시 `잔액≥판돈` 검증으로 패자도
정산 시 판돈 이상 보유, 미완료 매치는 칩 이동 없음), 제로섬(승팀 +판돈/패팀 −판돈, 0 까지만
차감해 음수 방지). 잔액 부족 시 무료 충전(`POST /api/me/chips/topup`, 잔액<200→500). 정산은
기존 `MatchResultRecorder`(@Transactional)에서 win/lose/rating 갱신과 같은 트랜잭션으로 원자적.
*계정 지갑 파트 폐기 → D-82 (방 단위 테이블 칩). 판돈/검증 뼈대는 유지.*

## D-80 (2026-06-12) — 선택적 코스메틱 아바타 업로드 (별도 테이블, D-02 보정)

좌석 아바타를 사용자가 직접 업로드한 이미지로 설정할 수 있게 한다(미설정 시 userId
해시 동물 이모지 — D-02 무관). **D-02 보정**: 아바타는 식별/연락 정보가 아니라
사용자가 임의로 바꾸는 **코스메틱**이며, ① `users` 화이트리스트는 **불변** —
별도 테이블 `user_avatars`(V5)에 저장(작은 128px PNG 를 `ImageIO` 로 리사이즈한
BYTEA + content_type + updated_at), ② 타입(PNG/JPEG)·크기 제한, ③ 언제든 삭제 가능,
④ 업로드 이미지는 사용자 제공 콘텐츠라 얼굴 사진 등 PII 표면이 늘 수 있음을 명시(기본은
이모지, 업로드는 본인 선택·책임). 조회는 게임 내 상대에게 노출되는 공개 코스메틱이라
`GET /avatars/{userId}`(비-`/api`, 공개 — 카드/캐릭터 정적 에셋과 동일 모델; `<img>`
태그가 Bearer 토큰을 못 싣는 제약도 해소). 업로드/삭제는 `POST`/`DELETE /api/me/avatar`
(인증, 본인만). 닉네임(좌석 표시)은 기존 `username` 을 그대로 쓰며 신규 컬럼 없음.

## D-79 (2026-06-08) — 모바일 재접속 강건화: 탈주 유예 30→120s + 포그라운드 즉시 재연결

모바일에서 게임 중 백그라운드(앱 전환·화면 잠금) 시 OS 가 ~10-15s 만에 WebSocket 을
죽이는데, 게임 소켓엔 하트비트가 없어(로비 소켓과 달리) 잠깐 자리를 비워도 30s 유예를
넘겨 탈주=패배로 처리되는 운영 위험이 있었다. **(A)** `mirboard.desertion.grace-seconds`
기본값을 30→**120s** 로 상향(`application.yml`, env `MIRBOARD_DESERTION_GRACE_SECONDS`
override; ReconnectBanner 3분 경고 안쪽). **(B)** `useStompRoom` 에 `visibilitychange`/
`online` 핸들러 추가 — 포그라운드 복귀 시 소켓을 즉시 재가동하고 `/resync`(REST, 소켓
상태 무관)로 화면을 곧바로 최신화해 유예 만료 전 빠르게 재접속. 명시적 '나가기' 는 여전히
즉시 탈주(불변), WAITING 끊김 즉시 leave 도 불변. 서버 탈주 로직(D-75)·스키마 무변경 —
설정값 + 클라 라이프사이클 핸들러만. 게임 소켓 하트비트 추가(좀비 소켓 감지)와 모바일
친화 탈주 정책은 후속 과제로 남김. *D-75 의 grace 기본 30s → 본 항목으로 변경.*

## D-78 (2026-06-08) — 카드 제출 애니메이션 (옵션 토글, 클라 전용)

카드 제출 시 중앙 트릭에 카드가 무애니로 툭 나타나던 것에 시각 피드백을 추가한다.
**Phase A**: 제출된 카드가 중앙에 페이드+업슬라이드+스케일로 등장(CSS `@keyframes
card-play-enter`, `.table-center-trick .hand-cards.play-enter` + play마다 바뀌는
React key 로 리마운트 재생). **Phase B**: 제출 좌석에서 중앙으로 날아오는 FLIP
애니(GameTable 의 `fly` 상태 + arena/center `getBoundingClientRect` 델타,
비행 중 정적 카드 `visibility:hidden` 으로 이중 표시 방지). on/off 는 `themeStore`/
`useSfx` 패턴을 미러한 `cardAnimStore`(localStorage `mirboard.cardAnim`, 기본 ON,
미저장+`prefers-reduced-motion` 시 기본 OFF, `main.tsx` 에서 `init()`)로 영속하고,
GameTable 헤더의 사운드 버튼 옆 토글(🎴/⏸)로 제어. 기존 폭탄/티츄 이펙트·사운드는
불변. 서버/스키마/STOMP 무변경 — 순수 클라. 빌드(tsc+vite) + vitest 73 그린.

## D-77 (2026-05-27) — Phase 20 후속: styles.css dead 규칙 정리

D-76 종료 직후 남긴 후속 항목(20f 메모 "styles.css 의 페이지-레벨 dead
규칙은 무해하여 점진 정리로 남김")을 처리. 메인/인증/방 만들기 모달/
공용 컴포넌트(Button/Badge/Input/Modal) 변형, 랭킹 테이블, target-score
picker, dragon/wish/phoenix 모달 등 shadcn 으로 전환된 페이지가 더는
참조하지 않는 약 70여 개 CSS 클래스 규칙을 제거. 게임판(GameTable,
SortableHand, ArenaChatBubbles) 이 여전히 의존하는 좌석/카드/오버레이/
패스 슬롯 규칙은 보존. styles.css 1218 → 660 줄(-558). 빌드(tsc+vite)
+ vitest 66 + check.sh fast 그린. 서버/스키마/프로토콜/UX 무변경.

## D-76 (2026-05-18) — Phase 20: shadcn/ui + Tailwind(Slate) + 라이트/다크 토글, 클라이언트 전체 단계적 재디자인

사용자 요청으로 클라이언트 UI 를 shadcn/ui 로 재디자인한다. 현재
`client/` 는 Tailwind 무설치 + 손제작 `styles.css`(1218줄, 다크 단일
토큰)이고 `button/input/a` 전역 reset 이 있어 Tailwind preflight 와
충돌(특히 GameTable 회귀 위험). 결정: Tailwind v3 도입하되
`corePlugins.preflight:false`(전역 reset 비활성, 기존 styles.css UI
보호) + `darkMode:'class'`, shadcn baseColor **slate**, CSS variables.
shadcn 토큰을 `:root`(light)/`.dark`(dark) 로 정의하고 기존
`tokens.css` 의 의미 토큰도 라이트/다크 양값으로 확장해 일시 공존 후
통합(도메인색 phoenix/dragon/mahjong 보존). 라이트/다크 **토글 완비**
(zustand `themeStore` + localStorage `mirboard.theme`, 기본 dark,
`<html>.dark`). 기존 `components/{Button,Badge,Input,Modal}` 은 shadcn
백엔드 어댑터로 재구현(props 시그니처 보존 → 호출부 무수정). 범위는
**클라이언트 전체**이나 **단계적**(20a 기반 → 20b 메인 → 20c 인증 →
20d RoomPage → 20e GameTable(대형, 최후) → 20f 정리). preflight off
덕분에 부분 마이그레이션 중 미전환 화면 안전. 각 Phase 끝 빌드/테스트
그린 + Phase 게이트 사용자 검토. 서버/스키마/프로토콜 무변경.

*구현 편차(20a~20f 완료): 기존 `components/{Button,Badge,Input,Modal,
Stack}` 는 "어댑터화"가 미전환(.app-shell 밖·레거시 CSS 의존) 화면을
깨므로, 어댑터 대신 페이지별 마이그레이션 후 20f 에서 **삭제**(전 소비자
ui/* 전환 완료). theme.css 의 shadcn slate 토큰은 전역 `:root`/`.dark`
정의(스코프 X), 레거시 `--color-*` 는 다크 유지+`html:not(.dark)`
라이트 오버라이드. shadcn 의 최소 base(box-sizing/border) 만
`.app-shell` 스코프 주입. 20e 는 GameTable 기하(styles.css) 보존하고
컨트롤/모달만 shadcn. styles.css 의 페이지-레벨 dead 규칙(.hub-*/
.auth-page 등)은 무해하여 점진 정리로 남김(후속).*

## D-75 (2026-05-18) — Phase 19: WS 끊김 빈 방 정리 + 게임중 탈주 패널티 + 패스 UI 통합

시연에서 (a) 새로고침/탭닫기 시 서버가 STOMP 끊김을 감지 못해 빈 방이
영구 잔존, (b) 게임 중 탈주에 대한 집계/제재 부재, (c) 패스 카드 선택
UI 분리 문제 확인. 결정: 서버에 `SessionSubscribeEvent`/
`SessionDisconnectEvent` 후킹(신규 in-memory `WsSessionRegistry`,
session→room 은 `^/topic/room/([^/]+)$` 구독 파싱)을 추가한다. WAITING
방에서 끊기면 즉시 leave/stopSpectating → 빈 방 즉시 소멸(관전자만 남은
방도 소멸, `room_leave.lua` 가 `spectators` SET 도 정리, 신규
`room_delete.lua`). IN_GAME 에서 끊기면 즉시 퇴장하지 않고 유예
(`mirboard.desertion.grace-seconds` 기본 30s) 후 재접속 안 하면 탈주로
확정. 탈주(IN_GAME 명시 '나가기' 또는 끊김 후 미복귀) = 탈주자
`desert_count`+1 · `lose_count`+1 · ELO 차감, **매치는 상대팀 승리**로
즉시 종료. 구현은 합성 `TichuMatchCompleted(winningTeam=상대팀,
deserterUserId)` 1회 발행으로 기존 `MatchResultRecorder` 경로를
재사용(중복 점수기록·중복 markFinished 없음, 봇 포함 매치 ELO 제외는
D-71 유지). leave/grace 양쪽 `RoomActionLock`+`status!=IN_GAME` 가드로
멱등. `users.desert_count` 컬럼은 `V4__desert_count.sql` 로 추가 —
게임행동 derived 값이라 D-02(식별/연락정보 금지) 위반 아님(V2 rating·
V3 is_bot 선례). in-memory 세션 레지스트리는 단일 인스턴스 MVP(D-03)
전제 — 다중 인스턴스 전환 시 Redis presence 로 교체(범위 밖). 패스
픽커는 별도 박스에서 `arena-actions`(액션 버튼 영역)로 통합 — 상태/
제출 로직 불변, JSX/CSS 배치만.

## D-74 (2026-05-18) — Phase 18: 메인 입장 멱등화 + room_leave ready 정리 + 방 만들기 모달 + "미르보드카페" 개편

시연에서 "방 나갔다 다시 입장하면 '이미 들어가 있다'(ALREADY_IN_ROOM)" 재입장
불가 버그 확인. 원인은 메인 `GameHubPage.handleJoin` 이 `roomsApi.join`(POST
`/join`)을 써서 `room:{id}:players` 잔존 시 `room_join.lua` 가 -4 를 반환하는
반면, `RoomPage` 진입은 멱등 `joinOrReconnect`(이미 참가면 RECONNECTED)를
쓰는 경로 불일치. 메인 입장도 `joinOrReconnect` 로 통일해 증상 제거. 부차로
`room_leave.lua` 가 방이 비어 destroy 할 때 `room:{id}:ready` SET 을 정리하지
않아 고아 키가 남던 것을 함께 삭제하도록 보강(레포 호출부·RedisConfig KEYS
동기화). `room_finish.lua` 는 결과 화면 resync 위한 600s 메타 보존이 의도라
변경하지 않음. 더불어 방 만들기를 메인 인라인 폼에서 버튼+모달(`Modal`
재사용)로 이동하고, 메인 제목을 "미르보드카페"로, 게임 카드의 선택 버튼을
제거하고 요약 설명 + 외부 티츄 위키로 새 탭 여는 "자세히" 링크(위키 URL 은
클라 측 게임별 하드코딩, 게임 카탈로그 API 계약 무변경)로 개편. 스키마/STOMP/
REST 계약 변경 없음.

## D-73 (2026-05-18) — 연속 페어(CONSECUTIVE_PAIRS) 최소 길이 3페어→2페어 보정

연속 페어는 이미 구현돼 있었으나 최소 길이가 3페어(6장)로 하드코딩
(`HandDetector.detectConsecutivePairs` 의 `n < 6`, 클라 `handType.ts` 의
`cards.length >= 6`)되어 있어 표준 티츄의 2페어(4장, 예 33-44) 계단을 낼
수 없었다. 사용자가 2페어 선택 시 클라 검출이 `'?'` 를 반환해 "내기"
버튼이 비활성 → "연페어가 안 된다" 증상. 서버/클라 최소 길이를 4장(2페어
이상, 짝수)로 낮춤. 검출 순서상 n=4 에서 SF-Bomb/FullHouse/Straight 불가,
Bomb 은 4장 동일 rank 만이라 2페어와 충돌 없음 → 비연속 2페어는 여전히
무효. HandComparator(동일 type+length rank 비교)·typePriority 는 길이
무관하게 동작하므로 변경 없음. Phoenix-연속페어 미지원·봇 enumerate·
Mahjong wish 강제(연속페어)는 기존 의도적 미지원으로 범위 밖.
`docs/rules-tichu.md` 의 길이 표기 동기화.

## D-72 (2026-05-18) — Docker socket 자동 감지 우선순위를 OrbStack 우선으로 변경

사용자 요청으로 `scripts/dev.sh` · `scripts/check.sh` 의 소켓 폴백 순서를
**Colima → OrbStack 에서 OrbStack → Colima 로** 뒤집음. `DOCKER_HOST` 가
이미 셋이면 그대로 존중하는 동작은 불변이라 명시적 override 사용자는 영향
없음. 두 런타임이 동시에 떠 있는 머신에서는 이제 OrbStack 소켓이 선택된다
(Colima 강제는 `DOCKER_HOST` export). Testcontainers in-container 경로는
양쪽 모두 `/var/run/docker.sock` 매핑이라 변경 없음. README "컨테이너
런타임" 을 OrbStack 권장으로 재정렬. D-69 의 우선순위 결정을 번복.

## D-71 (2026-05-17) — Phase 16: 준비-시작 모델 + 메인 재구성 + 랭킹 + 화면 정리 (9건)

시연 피드백 9건. 핵심 결정: (1) 게임 시작 모델을 **capacity 자동시작
→ 전원 준비 시 자동시작** 으로 변경. `room_join.lua` 의 정원도달
IN_GAME 자동전이를 폐기하고, 신규 `room_ready.lua` 가 `SCARD(room:{id}
:ready)==capacity && #players==capacity` 일 때 원자적으로 WAITING→
IN_GAME 전이 + `GameStartingEvent` 트리거. 봇은 join 직후 서버가 자동
ready 처리(솔로=봇3 자동준비+호스트 수동준비, all-bot 시나리오는 자동
시작 보존). 호스트/시작버튼 없음. 신규 `POST /api/rooms/{id}/ready`,
신규 Redis 키 `room:{id}:ready`(SET, TTL 21600s), Room DTO 에
`readyUserIds` 추가(기존 방 메타 WS 로 운반 — STOMP destination 무변경).
(2) 전적: 봇 포함 매치도 win/lose·match_result 는 기록하되 **ELO(rating)
만 제외**(인플레 방지). 사람 4인 매치는 기존대로 win/lose+ELO. (3) 신규
`GET /api/users/ranking`(봇 제외, rating desc) + 메인 랭킹 섹션. (4)
네비게이션: 게임별 로비 페이지/라우트(`/games/:id/lobby`, LobbyPage)
제거하고 `/games` 메인에 게임소개+방생성(게임선택)+방목록+관전+로비채팅
+랭킹 통합. (5) 게임 화면: 상단 "현재 차례" 제거, "내 손패" 문구 제거,
액션 버튼을 경기장 내 남쪽 좌석 아래로 이동, 매치 종료 후 "메인으로"
복귀 버튼. State Hiding·개인정보 최소화·users 컬럼 화이트리스트 불변.

## D-70 (2026-05-17) — Phase 15: 시연 UX 6건 (전부 클라 전용)

솔로 시연 중 요청된 게임 화면 UX 6건을 **클라이언트만으로** 구현 —
서버 로직·STOMP 프로토콜·DB·스키마 무변경. (1) 매치 종료 화면에 승리팀
강조 + 라운드별 점수표 + 최종 합계, 게임 중 경기장 상시 스코어보드
(누적 totals 는 서버 `tableView.matchScores` 사용). (2) 패스 단계에서
줄 사람에게 배정한 카드는 손패에서 제거하고 그 줄 사람 슬롯은 정적
칩으로 고정(선택지 제거; 되돌리기는 기존 "초기화"). (3) 센터에 낸 카드
비겹침(`.table-center-trick .hand-cards` 음수 마진 제거, gap 6px).
(4) 티츄/그랜드 선언 시 기존 `EffectsOverlay` 패턴 재사용해 중앙 대형
배너(effectStore 에 `TICHU_DECLARED` kind + text 추가). (5) 인게임
채팅을 발신자 좌석 근처 말풍선으로 ~5s 노출 후 fade(신규
`ArenaChatBubbles`, `useRoomChatStore` 재사용) — 기존 우측 `RoomChat`
패널은 그대로 유지. (6) 턴 제한 카운트다운을 클라가 `TURN_CHANGED`
수신 시각(store `turnStartedAt`)+방 `turnSeconds` 로 로컬 계산해 표시;
실제 타임아웃 강제는 서버 `TurnTimeoutScheduler` 그대로(표시는 근사).

사용자 결정(AskUserQuestion): 점수는 종료+상시 둘 다, 채팅은 패널 유지
+말풍선, 턴 타이머는 클라 로컬(프로토콜 무변경). 한계: #1 라운드별
표는 클라 `ROUND_ENDED` 누적이라 게임 중/직후 재접속 시 표가 비고
(누적 totals 는 복구 가능) — 시연 단일 세션 수용, 서버 payload 확장은
별도 결정으로 보류. State Hiding 불변(말풍선·스코어보드는 공개 정보만).

## D-69 (2026-05-17) — OrbStack Docker socket 자동 감지 추가

`scripts/dev.sh` · `scripts/check.sh` 의 소켓 자동 감지를 Colima 전용에서
**Colima → OrbStack 순 폴백**으로 확장. `DOCKER_HOST` 미설정 시
`~/.colima/default/docker.sock` 우선(기존 동작 불변), 없으면
`~/.orbstack/run/docker.sock` 사용. Colima 사용자 동작은 그대로이고 OrbStack
사용자도 wrapper 가 자동으로 올바른 소켓을 잡는다. Docker Desktop 은 기본
context 로 동작하므로 분기 불필요. Testcontainers in-container 경로는 두 런타임
모두 `/var/run/docker.sock` 매핑이라 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`
동일. 런타임/프로토콜/DB 무변경 — 순수 개발 도구. README "컨테이너 런타임"
섹션에 OrbStack 설치 대안 + 자동 감지 동작 명시.

*변경 → D-72* (우선순위를 OrbStack → Colima 로 뒤집음)

## D-68 (2026-05-17) — Phase 14: 로컬 실행 wrapper `scripts/dev.sh`

Fly.io 배포와 별개로 로컬 풀스택을 손쉽게 올리도록 `scripts/dev.sh` 추가
(`scripts/check.sh` 패턴 재사용 — set -e, repo root cd, Colima socket 자동
감지, 서브커맨드, --help). 서브커맨드: `up`(docker compose postgres+redis +
Flyway 마이그레이션, 멱등) / `server`(infra 보장 후 bootRun :8080) /
`client`(Vite :5173) / `bundled`(bootRun -PbundleClient, :8080 단독) /
`all`(서버 백그라운드+클라 포그라운드, trap 으로 Ctrl-C 정리) / `down [--purge]`.

핵심 사실 명문화: 로컬 실행은 기존에도 가능했고 부족했던 건 오케스트레이션
편의뿐 — `application.yml` dev 기본값이 docker-compose 자격증명과 일치하고
`MIRBOARD_JWT_SECRET` dev 기본값(≥32B)이 내장돼 **추가 env export 불필요**
(운영만 secret 주입). 서버/프로토콜/DB 무변경, 순수 개발 도구.

호스트 5432/6379 를 mirboard 외 컨테이너가 점유 시 어떤 컨테이너인지 명시하고
중단(check_port_conflict). 포트 충돌 정책은 사용자 결정으로 **기본 5432 유지 +
명확한 안내** (override 파일/기본 포트 변경 안 함 — 다른 프로젝트와 동시 구동
시 그 컨테이너를 잠시 끄고 재시도). README "로컬 실행(빠른 시작)" 섹션 신설,
기존 raw 명령은 "수동/디버깅용" 으로 보존.

## D-67 (2026-05-17) — Phase 13F: 시연 UX 보정 4건 (클라)

- **#1 내 손패 안 겹침**: Phase 12 의 `.hand-cards` 음수 마진 overlap 을 트릭
  (`.table-center-trick .hand-cards`) 로만 한정. 내 손패는 gap 6px 복귀.
- **#2 패스 자동 제출**: 좌/파/우 3장 모두 배정되면 `pass.submit` 버튼 없이
  useEffect 가 즉시 PASS_CARDS 전송. 되돌리기는 clear 버튼/슬롯 재클릭으로 3장
  확정 전 가능 (확정 후 자동 — 사용자 선택). submit 버튼 제거.
- **#3 카드 문양+숫자만**: D-48 트럼프 SVG 렌더 제거 — `CardChip` 이 항상
  글리프(◆⚔⛩★)+숫자/특수 이모지(🐕🐉🔥/1) 텍스트로. `cardAssetSrc` 헬퍼·테스트는
  유지 (추후 실제 카드 assets 일괄 교체 시 이미지 모드 재도입 예정).
- **#4 모바일 경기장**: 보드 폴백 breakpoint 640 → 768px 확대 + width:100% +
  radial 배경 유지, <420px 는 좌석 1열. 좁은 창/폰에서 항상 경기장 표시.

순수 클라, 서버/프로토콜 무변경. 클라 build:check + test + build 그린.

## D-66 (2026-05-17) — Phase 13: 시연 UX/기능 7건

배포 시연 요청 7건.
- **#1 패스 UX 뒤집기**: 슬롯 먼저 → 카드 먼저. tichuStore `pendingPassCardKey`
  + `selectPassCard`/`assignPassSlot(slot)`. `passSelection`/PASS_CARDS 계약 불변.
- **#2 MakeWish J/Q/K/A**: `rankGlyph` export, 모달 표시만 글자화 (서버 int).
- **#3 폴링→WS**: `RoomLobbyEventPublisher` 가 per-room `/topic/room/{id}/meta`
  추가 발행 (ROOM_META_UPDATED/ROOM_DESTROYED). 클라 `useRoomMeta` 훅 신규,
  RoomPage 2초 폴링 제거.
- **#4 내기 버튼**: `selectedCombo==='?'` 조건 추가 (유효 조합 hint, 서버 최종 검증).
- **#5 기본 랭크 정렬**: `sortedHand` 가 sortOrder 비면 byRank 정렬. 정렬 버튼/
  `sortHandByRank`/`restoreServerOrder` 제거. 드래그 수동 재배열 유지, 새 손패마다
  리셋(라운드 단위 자동 랭크순).
- **#6 개인 턴 타이머**: `Room.turnSeconds` (프리셋 끔/30/60/90, 기본 끔=0)
  targetScore 패턴 미러 배선. `TurnTimeoutScheduler` (ScheduledExecutorService +
  per-room generation gen-guard + RoomActionLock 공유) + `TimeoutActionPolicy`
  (결정적: GiveDragon>Ready>PassCards>PassTrick>약한 단일카드, LegalActionEnumerator
  재사용). hook: GameStompController/BotScheduler/TichuRoundStarter 액션 후
  `onTurnAdvanced`. turnSeconds=0 → no-op (기존 동작 완전 호환). **단일 머신 전제 —
  타이머 in-memory. 다중 인스턴스 전환 시 Redis 동기화 필요 (범위 외).**
- **#7 Dragon 양도 즉시**: 좌석 클릭 시 즉시 onConfirm, 확인 버튼 제거.

서버 전체 회귀 3m1s + bot-stress 3 + 클라 build/test 그린. 신규 IT:
TurnTimeoutSchedulerIT (비-봇 host idle → 타임아웃 자동진행 매치 완주),
RoomLobbyEventPublisherTest 4, RoomController turnSeconds 2 케이스.

## D-65 (2026-05-17) — Phase 12E: 방 목표점수 + 조합명 rank + 카드 포갬

배포 시연 추가 요청 4건.

1. **팀 목표점수** — 매치 종료 점수를 방 생성 시 선택 (프리셋 300/500/700/1000,
   기본 1000). `Room.targetScore` 필드 + `room_create.lua` HSET + `RoomService.createRoom`
   오버로드 + `GameStartingEvent.targetScore` + `TichuMatchState.targetScore` +
   `effectiveTarget()` (구 JSON/0 → 1000 폴백) + `isMatchOver` 가 목표점수 사용.
   `RoomController.CreateRequest.targetScore`. 클라 LobbyPage 프리셋 버튼.
   매치 종료 룰 변경이므로 `rules-tichu.md` §14 갱신 필요. 단위/IT 케이스 추가.
2. **조합명 rank** — `handType.ts` `comboLabel(cards)` 가 "페어2"/"풀하우스5"/
   "스트레이트9" 형식 (대표 rank: PAIR/TRIPLE/BOMB=그 rank, FULL_HOUSE=트리플 rank,
   STRAIGHT/SFB/CONSEC=최고 rank, SINGLE=카드/특수명). 트릭 메타도 동일 한국어화.
3. **손패 카드 포갬** — `.hand-cards` gap 제거 + 음수 마진(-20px) + hover/selected
   z-index. SortableHand·트릭 공통 적용.
4. **낸 카드 포갬** — 위 `.hand-cards` 변경이 트릭 표시(`table-center-trick`)에도
   동일 적용 (공통 클래스).

서버 전체 회귀 1m31s + 클라 빌드/테스트 그린.

## D-64 (2026-05-17) — Phase 12D: 티츄 선언자 배지 강화

코드상 이미 구현돼 있던 선언 표시 (`TableView.declarations` + `TichuDeclared`
공개 이벤트 + 리듀서 + `.declared` div) 가 raw enum 텍스트라 약함. 좌석 헤더에
한국어 pill 배지로: TICHU → "🔔 티츄!" (노랑), GRAND_TICHU → "👑 그랜드 티츄!"
(주황 + pulse 애니메이션). 데이터 흐름은 그대로 (변경 X), 표시 레이어만 강화.
`styles.css` `.declared` pill/대비/`.grand` 펄스 추가. #1 (D-61) 401 픽스로 UI
가 정상 렌더되면 함께 잘 보임.

## D-63 (2026-05-17) — Phase 12C: 선택 패 조합명 표시 (클라 hint)

`client/src/features/tichu/handType.ts` (순수 함수) — 선택 카드 조합을 표시용
으로 판별. SINGLE/PAIR/TRIPLE/FULL_HOUSE/STRAIGHT/CONSECUTIVE_PAIRS/BOMB/
STRAIGHT_FLUSH_BOMB + Mahjong(STRAIGHT rank 1) + Phoenix best-effort
(PAIR/TRIPLE만, 복잡 케이스 null). **서버 HandDetector 가 룰 단일 진실공급원
(Phase 10) — 본 함수는 UI hint 전용**, 합법성 강제는 ActionValidator 가 계속
담당. divergence 시 사용자 혼란 완화: 라벨 "선택: X", 서버 reject 메시지 그대로
노출. GameTable Play 영역에 "선택: 스트레이트 (5장)" 표시. 단위 테스트 12개
(handType.test.ts). 클라 48 그린.

## D-62 (2026-05-17) — Phase 12B: PlayCard 후 HandDealt 재발행

배포 시연에서 "낸 패가 손에서 안 사라짐". 원인: `TichuEngine.applyPlayCard` 가
`Played` 만 발행하고 `HandDealt` 미발행. 클라 `useStompRoom` 은 `HAND_DEALT` 로만
`applyPrivateHand` 호출 → 손패 stale (서버 상태는 정상, UI 만 안 갱신).
`applyPassCards`/`advanceFromDealing` 은 HandDealt 발행하던 패턴과 불일치.
fix: `events.add(Played)` 직후 `events.add(new HandDealt(seat, newHand,
newHand.size()))` — 낸 카드 제거 후 손패. HandDealt 는 `isPrivate()`=true →
broadcaster 가 `/user/queue` 로만 라우팅 (State Hiding 유지). 모든 return 분기
(Dog/일반/트릭종료/라운드종료) 가 공유하도록 events 에 일찍 추가. 봇은 stateStore
직접 읽으므로 영향 없음. `TichuSpecialCardScenarioTest` 에 케이스 추가 (PlayCard
후 seat HandDealt + 잔여 손패 일치). 전체 서버 회귀 1m25s 그린.

## D-61 (2026-05-17) — Phase 12A: SecurityConfig 비-API default-permit

배포 시연에서 `/cards/*.svg`, `/characters/*.webp` 가 HTTP 401. 원인: `SecurityConfig`
의 GET permitAll 열거 목록에 정적 자산 디렉토리(`/cards/**` 등) 누락 + SPA 경로가
옛 이름(`/hub`/`/lobby`/`/room`)이라 실제 라우트(`/games`/`/rooms`)와 불일치 →
`anyRequest().authenticated()` 폴백 → 401. 열거 방식이 route-drift 버그를 반복
유발하므로 **인증 표면을 `/api/**` 로 한정**하고 나머지(`anyRequest`)는 permitAll
로 전환. STOMP 는 `/ws` 핸드셰이크 permitAll + CONNECT 단계 별도 인증이라 영향
없음. 트레이드오프: 비-API default-permit → **규약: 민감 HTTP 엔드포인트는 반드시
`/api/**` 하위에 둘 것**. `AuthFlowIntegrationTest` 에 정적/SPA 미인증 non-401 +
`/api/**` 인증 유지 회귀 케이스 3개 추가. 503 은 별개(콜드스타트 추정, 본 Phase 외).

## D-60 (2026-05-15) — Phase 11: 검증 자동화 wrapper (scripts/check.sh)

자주 쓰는 검증 명령 7 개를 `scripts/check.sh` 단일 진입점에 묶음. 가치:
1. Colima `DOCKER_HOST` / `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` 자동 감지 —
   매번 8단어 prefix 입력 불필요
2. 자주 쓰는 그룹 테스트 단축 — `./scripts/check.sh rules` 가 룰 도메인
   (`*.card.*`, `*.hand.*`, `*.scoring.*`, `*.action.*`, `*.invariant.*` +
   simulation/lifecycle/match-state)
3. 봇 stress flag 망각 방지 — `./scripts/check.sh bot-stress 50` → 자동
   `-Dmirboard.bot.simulation-count=50`
4. `.husky/pre-commit` 이 `exec scripts/check.sh fast` 로 위임 — 검증 로직
   single source

7 서브커맨드: `fast` / `rules` / `server` / `client` / `all` / `bot-stress [N]` /
`infra`. `--help` 로 카탈로그 출력. 기존 raw gradle/npm 명령은 그대로 유지 (디버깅 시
직접 호출 가능). CI workflow 는 의도적으로 미수정 — ubuntu runner 환경이 다르고,
스크립트 의존하면 CI 디버깅 layer 추가됨.

자동 환경 감지 로직: `[ -z "$DOCKER_HOST" ] && [ -S "$HOME/.colima/default/docker.sock" ]`
시 export. CI Ubuntu runner 에선 socket 부재 → export 생략 (CI 회귀 0).

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
