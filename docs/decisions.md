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
