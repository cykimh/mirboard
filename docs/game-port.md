# GameEngine 포트 설계 (D-97 설계 · D-98 구현)

> 상태: **구현 완료** (S1, D-98) · 설계 2026-07-30 · 반영 2026-07-30 · 엔진 타이머 추가 D-128(2026-10-05)
> 이 문서는 **계약 정본**이다. 포트를 바꾸면 여기를 먼저 고친다.
> 구현 순서와 세션 분할은 `docs/plans/multi-game-sessions.md`.

## 0. 착수 전 → 현재

D-97 시점에 `CLAUDE.md` 와 D-06/D-11 의 "새 게임 = 패키지 + `GameDefinition` Bean 등록"은
**카탈로그(`GET /api/games`)까지만 사실**이었다. S1(D-98)이 그 간극을 닫았다.

| 확인 | D-97 시점 | 현재 (D-98) |
| --- | --- | --- |
| `GameEngine` / `GameAction` / `GameEvent` | 메서드 **0개** (빈 마커) | 실제 표면 (§1) |
| `GameDefinition.newEngine()` | 호출부 **0건** (죽은 코드) | `GameEngineProvider.forRoom` 단일 경로 |
| infra → `domain.game.tichu` 의존 | **10파일** | **1파일** (`RoomChipService` — §2 의도적 잔여) |
| `GameStompController` | `@Payload TichuAction` 타입 고정 | `engine.actionType()` 로 게임별 분기 |

CLAUDE.md 의 "새 게임 = Bean 추가" 문구는 이제 **인게임까지 참**이다. 남은 잔여 1파일의
근거는 §2(칩은 포트 밖) 에 있다.

## 1. 포트 표면 — 티츄 구현에서 역산

지금 인게임이 실제로 요구하는 것은 6가지이고, 기본값이 있는 **선택형 확장** 둘(⑦ 엔진 타이머 D-128, ⑧ 미리보기 D-229 — 구현 게임은 씨앗포·얏추 D-268)이 붙었다.
각각의 현재 구현과 "요트가 깨는 지점"을 같이 적는다 — **포트는 두 번째 게임이 아니라 세 번째 게임에서
검증**되기 때문이다.

| # | 책임 | 현재(티츄) | 스컬킹 | 요트가 깨는 지점 |
| --- | --- | --- | --- | --- |
| 1 | 액션 적용 | `TichuEngine.apply(state, seat, action) → (newState, events)` | 동일 | 동일 |
| 2 | 상태 직렬화 | `TichuGameStateStore` (JSON→Redis) | 동일 | 동일 |
| 3 | 공개 뷰 | `TichuStateMapper.toTableView` | 동일 | 동일 |
| 3' | **비공개 뷰** | `toPrivateHand(state, seat)` | 동일(손패) | **없음** — 점수판이 공개 → Optional |
| 4 | 단계 이름 | `phaseName(state)` | 입찰/플레이 | 굴림/기록 |
| 5 | 라운드·매치 진행 | `MatchProgressService.onRoundEnd` + `TichuMatchState.isMatchOver` | **10라운드 고정** | **12칸 채우면 종료** |
| 6 | 합법 액션 | `LegalActionEnumerator.enumerate` + `TimeoutActionPolicy.choose` | 동일 | ~~조합 폭발(주사위 고정 2^5 × 남은 칸)~~ *실측(요트 S2): 홀드와 기록이 별개 액션이라 곱이 아니라 합 — 최대 45개* |
| 7 | 엔진 타이머 *(선택, D-128)* | 없음(기본 empty) | 없음(기본 empty) | ~~해당 없음~~ *요트가 쓴다 — 차례 전체 제한(D-183, §2). 원카드는 경쟁 창* |

### 확정한 인터페이스

```java
public interface GameEngine {                       // per-room. newEngine(ctx) 로만 생성.
    GameContext context();

    // ② 상태 직렬화 — 저장 위치/포맷은 게임이 정한다.
    Optional<GameState> loadState();
    void saveState(GameState state);

    // ① 액션
    Class<? extends GameAction> actionType();       // 역직렬화 seam (§5 열린 질문 2)
    Result apply(GameState state, int seat, GameAction action);   // 위반 → GameActionRejectedException

    // ④ 단계 이름 / 진행 질의
    String phaseName(GameState state);
    List<Integer> pendingSeats(GameState state);    // 오름차순. 동시 대기 가능(티츄 Dealing/Passing)
    default int pendingSeat(GameState state) { ... } // 첫 좌석 또는 -1 (타임아웃 타이머용)
    boolean isRoundOver(GameState state);
    boolean isMatchOver();                          // 매치 상태는 엔진이 소유 — 인자 없음

    // ③ 뷰
    Object publicView(GameState state);
    Optional<Object> privateView(GameState state, int seat);  // 요트는 empty

    // ⑥ 봇 / 타임아웃
    List<GameAction> legalActions(GameState state, int seat);
    default GameAction botAction(GameState state, int seat, Random random) { ... }  // 기본 균등분포
    GameAction timeoutAction(GameState state, int seat);

    // ⑦ 엔진 타이머 (D-128) — 기본 없음. 시간이 지나면 저절로 일어나는 전이(§2)
    default Optional<Duration> timer(GameState state) { return Optional.empty(); }  // 지금부터 남은 시간
    default Optional<Result> onTimer(GameState state) { return Optional.empty(); }  // 만료 시 전이

    // ⑧ 미리보기 (D-229) — 기본 없음. 상태를 바꾸지 않는 실시간 표시(§ "실시간 미리보기")
    default Optional<GameEvent> preview(GameState state, int seat, GameAction input) { return Optional.empty(); }

    // ⑤ 라운드 · 매치 진행
    Advance advance(GameState newState, List<GameEvent> outbound);
    DesertOutcome desert(int seat, long deserterUserId, List<GameEvent> outbound);

    record Result(GameState newState, List<GameEvent> events) {}
    record Advance(boolean roundCompleted, boolean matchCompleted) {}
    enum DesertOutcome { NOT_APPLICABLE, MATCH_CONTINUES, MATCH_ENDED }
}
```

*(D-102 변경)* `desert` 는 원래 `boolean`("매치를 강제 종료했는가")이었다 — 티츄의 2:2
전제(탈주=상대팀 승리 종료)가 계약에 박혀 있어 `DesertionService` 가 true 면 무조건 방을
FINISHED 로 만들었다. 스컬킹의 "남은 사람끼리 계속"(D-104)은 2치로 표현할 수 없어 3치로
바꿨다: `MATCH_CONTINUES` 면 인프라는 이벤트만 브로드캐스트하고 방을 IN_GAME 으로 유지한
채 봇/타임아웃을 재무장한다. 티츄는 `MATCH_ENDED`/`NOT_APPLICABLE` 만 쓴다.

*(D-122)* `NOT_APPLICABLE` 을 받은 '나가기'를 인프라가 일반 leave(좌석 목록 `LREM`)로 넘기는
것은 **`isMatchOver()` 가 true 일 때뿐**이다(티츄 리매치 대기). 매치가 진행 중이면 — 예컨대
`MATCH_CONTINUES` 게임에서 이미 탈주한 좌석이 다시 '나가기' — 좌석을 그대로 둔다. 진행 중
매치의 좌석 인덱스는 액션 좌석 판정·비공개 이벤트 라우팅이 쓰므로 당기면 손패가 남에게 간다.
새 게임은 이미 탈주한 좌석에 `NOT_APPLICABLE` 을 돌려주면 된다(별도 처리 불필요).

`GameState` · `GameAction` 은 마커, `GameEvent` 는 `envelopeType()` + `privateSeat()` +
`sequenced()` 만 노출한다(브로드캐스터가 게임을 모른 채 라우팅할 최소치). `GameContext` 는
`(roomId, playerIds, targetScore, stake, botSeats, options, turnSeconds)` — 방 설정이 엔진에 들어오는 유일한 창구다(`options` 는 D-176, `turnSeconds` 는 D-183).

*(D-126)* `GameEvent.sequenced()`(기본 true)가 false 면 브로드캐스터는 방 순번을 발급하지
않고 envelope 의 `seq` 를 비운다. 방 순번은 클라가 **공개 토픽**에서 구멍을 찾는 기준이라,
비공개 이벤트가 순번을 쓰면 그 이벤트를 받지 않는 클라에게 다음 공개 이벤트가 항상 구멍으로
보여 resync 를 부른다. 새 게임은 비공개 이벤트를 false 로 두는 것이 맞다(티츄·원카드는
`!isPrivate()` — 원카드는 D-129). 기본값이 true 인 것은 스컬킹이 아직 그 구멍을 라운드 경계 resync 로 쓰고
있어서다(D-103) — 옮기려면 그 resync 에 기대는 화면 상태를 먼저 이벤트로 옮겨야 한다.

### D-98 구현에서 고친 3곳

설계(D-97)와 다른 부분. 이 문서가 계약 정본이므로 표면은 위 코드가 정본이다.

| 설계 | 구현 | 이유 |
| --- | --- | --- |
| `isMatchOver(state, MatchProgress)` | `isMatchOver()` | 티츄 매치 상태는 **팀 점수 + MVP 기여도**라 게임 중립 `MatchProgress` 로 손실 없이 표현 불가. 매치 상태를 엔진이 소유하고 인프라에 노출하지 않는다 |
| `pendingSeat` 단수 | `pendingSeats` 복수 (+ `pendingSeat` default) | Dealing/Passing 은 **여러 좌석 동시 대기**. 단수면 좌석 3 봇이 좌석 1 사람의 선언을 기다리며 멈춘다 |
| `initialState(seatCount, seed)` | **미채택** | 라운드 시작은 게임 도메인의 `GameStartingEvent` 리스너가 계속 맡아 호출부가 0건. 호출부 없는 포트 메서드가 바로 `newEngine()` 의 실패 모드였다 |

`roundScores(state)` 도 두지 않았다 — 좌석별 점수를 인프라가 쓸 일이 없고, 라운드 점수
누적은 `advance` 안에서 게임이 자기 매치 상태에 직접 한다. 대신 `desert`(탈주 강제 종료)와
`advance`(진행 결과 통보)가 들어왔다.

### 설계 판단 세 가지

**(1) 팀을 포트에서 뺀다.** 티츄는 2:2 고정이지만 스컬킹·요트는 개인전이다. 점수를
`Map<Team,Integer>` 가 아니라 **좌석별 `Map<Integer,Integer>`** 로 다루고, 팀 합산은
티츄 내부 관심사로 내린다. `TichuMatchState.scoresByTeam()` 은 티츄 안에 남는다.

**(2) 비공개 뷰는 Optional.** 요트에는 손패가 없다. 여기서 "모든 게임에 privateView 가
있다"고 가정하면 요트가 빈 객체를 반환하는 거짓 구현을 강요당한다. State Hiding(D-01)은
"비공개가 있으면 반드시 본인 큐로만"이지 "반드시 비공개가 있다"가 아니다.

**(3) 매치 종료 판정을 엔진이 한다.** 지금은 `MatchProgressService` 가 티츄 규칙
(1000점)을 알고 있다. 스컬킹은 10라운드 고정, 요트는 12칸 소진이라 종료 조건이 전부
다르므로 인프라가 알면 안 된다.

## 2. 무엇을 포트에 넣지 않는가

의도적으로 **밖에** 둔다 — 넣으면 모든 게임이 티츄 모양을 강요당한다.

| 제외 | 이유 |
| --- | --- |
| 팀(`Team` enum) | 개인전 게임이 다수 |
| 칩/판돈(D-82) | 티츄 매치 종료에 묶인 별건. 신규 게임은 `stake=0` 으로 시작 |
| ELO·전적 | 게임별 `user_game_stats`(D-115)에 각 게임의 매치 기록기가 직접 쓴다. 공통 규칙은 `domain.game.scoring`(`RatedMatchPolicy`·`EloCalculator`·`UserGameStatsService`) — 아래 "매치 기록기 계약" *(D-115 정정: 예전 이 행은 "`users.rating` 단일 컬럼이라 스키마 결정 필요"였다)* |
| 좌석 수 4 고정 | 가변으로 감 — §3 |
| 트릭·리드수트 | 트릭테이킹 계열만의 개념 — 포트에 없음. *(D-101 정정: 티츄 트릭 모델은 조합 기반(`Hand`·passedSeats·wish)이고 리드수트 개념이 없어 교집합 0 — 공유 모듈 없이 스컬킹 전용 `domain/game/skullking/trick/` 으로 구현됐다)* |

**칩을 뺀 결과 — infra 의 유일한 티츄 잔여**: `RoomChipService`(infra.ws)는
`TichuGameDefinition.ID` / `TichuMatchCompleted` / `Team` 을 계속 직접 참조한다. 칩 정산은
"어느 팀이 이겼는가"에 붙어 있어서 포트로 올리려면 팀 개념을 다시 끌어올려야 하고, 신규
게임은 `stake=0` 으로 시작하므로 지금 일반화하면 **쓰이지 않는 추상**이 된다. 스컬킹에
내기를 붙이는 시점에 "승자 집합"만 다루는 게임 중립 정산 이벤트를 별건으로 검토한다.

### 매치 기록기 계약 (D-115·D-117·D-131)

ELO·전적은 포트 밖이지만, 새 게임의 매치 기록기(`MatchResultRecorder`·`SkullKingMatchRecorder`
같은, 게임별 매치 종료 이벤트 리스너)는 다음 세 가지를 지켜야 한다.

1. **ELO 적용 여부는 `RatedMatchPolicy.eloApplies(playerIds)` 하나로 판정한다.** 봇(D-71)이나
   게스트(D-117)가 한 명이라도 낀 매치는 전원 미적용이다(승패·탈주는 기록). 조건을 기록기마다
   따로 쓰면 한쪽만 고쳐진다 — 실제로 D-117 전에는 두 기록기에 `hasBots` 가 각각 있었다.
2. **비봇 참가자마다 `user_game_stats` 행을 남긴다**(`UserGameStatsService.record`, ELO 미적용이면
   `newRating=null`). 게스트 정리(`GuestAccountSweeper`)가 이 행을 "매치를 끝낸 증거"로 쓴다.
   참가자 테이블에만 쓰고 stats 를 빠뜨리면, 그 게스트는 48h 뒤 정리 대상이 됐다가 참가자 FK
   때문에 삭제가 매번 실패한다(그 행만 건너뛰고 warn — 정리 자체는 커서로 계속 진행).
3. **기록 실패는 진행 경로로 새지 않는다(D-130·D-131).** 매치 종료 이벤트는 동기 로컬 발행(D-116)이라 리스너
   예외가 그대로 발행자(액션·봇·타이머·탈주 경로)로 올라가 마지막 방송·`markFinished`·턴 데드라인 취소(티츄는
   리매치 대기·칩 정산)를 건너뛴다 — 방이 IN_GAME 에 남는다. 리스너가 **하나**면 엔진 어댑터의 발행 지점에서
   `RuntimeException` 을 잡아 결과를 실은 ERROR(스택 포함)로 남긴다(원카드·스컬킹). **둘 이상**이면 기록기가
   `TransactionTemplate` 으로 트랜잭션 경계를 직접 감싸 본문·커밋 예외를 삼킨다(티츄 — 발행 지점에서 잡으면
   멀티캐스터가 첫 예외에서 멈춰, 리스너 순서에 따라 칩 정산이 빠진다. `@Transactional` 본문 안의 try 로는 커밋
   실패를 못 잡는다). 고정: `infra.ws.*RecorderFailureTest`.
   **이 격리는 진행 경로가 트랜잭션 밖에서 매치 종료 이벤트를 발행한다는 전제에 선다(세 게임 공통).** 기록기는 기본
   전파(REQUIRED — 티츄 `TransactionTemplate`, 스컬킹·원카드 `@Transactional` 리스너)라 바깥 트랜잭션이 있으면 거기에
   참여하고, 기록 실패는 바깥을 rollback-only 로 표시한 채 삼켜져 바깥 커밋에서 `UnexpectedRollbackException` 으로 샌다.
   진행 경로(컨트롤러·봇·두 스케줄러·탈주·REST leave)에 `@Transactional` 을 얹지 말 것. `REQUIRES_NEW` 로 바꾸지 않은 것은
   `MatchResultRecorderIT` 가 테스트 트랜잭션 안에서 만든 사용자를 기록기가 같은 트랜잭션에서 보는 것에 기대기 때문이다.

`users` 를 FK 로 참조하는 테이블을 **매치와 무관한 용도**(신고·역할처럼)로 새로 만들면
`UserRepository.findExpiredGuestIds` 에 `NOT EXISTS` 를 더할 것. 매치 참가자 테이블은 2번이
지켜지는 한 수정이 필요 없다. `GuestAccountSweeperIT` 가 이 경계를 고정한다.

### 그런데 "포트에 안 넣는다"가 UI 를 자동으로 고쳐주지는 않았다 (D-106)

칩·팀·목표 점수를 포트 밖에 두었어도 **방 생성 UI 와 대기실은 셋을 계속 노출**했다. 포트가
게임 로직을 격리했을 뿐, "이 게임이 이 설정을 쓰는가"를 물어볼 자리가 없었기 때문이다.
스컬킹 방에서 판돈을 켜면 `RoomChipService` 가 조용히 return 해 **칩은 안 생기는데 봇만
금지**됐다 — 포트 밖으로 뺀 관심사가 UI 에서 되돌아온 셈이다.

그래서 포트에 표면을 **하나만** 더 열었다:

```java
default Set<RoomOption> supportedRoomOptions() { return EnumSet.noneOf(RoomOption.class); }
```

- `RoomOption` = `TARGET_SCORE`·`TEAMS`·`BETTING`. 옵션이 늘어도 enum 상수 1개이지
  포트 메서드가 아니다 — 표면을 좁게 유지한다는 §1 원칙 그대로.
- **기본은 빈 집합(옵트인)**. 새 게임은 한 줄도 안 써야 올바르게 동작한다. 스컬킹은
  이 메서드를 재정의하지 않으며, 그것이 "10R 고정·개인전·칩 미지원"의 정확한 선언이다.
- 모든 게임에 통하는 설정(방 이름·인원·턴 제한·봇 채우기·**좌석 순서 정책**)은 여기
  들어가지 않는다. 들어가는 것은 **게임 구조에 묶여 어떤 게임에는 아예 뜻이 없는** 것뿐이다.

**여기서 한 번 틀렸다 (D-106 정정).** 처음엔 `TEAMS` 를 좌석 정책 UI 의 on/off 스위치로
썼는데, 좌석 정책은 위 목록의 "모든 게임에 통하는 설정"이다 — `domain.game` 에
`TeamPolicy` 참조가 0건이고 `RANDOM` 은 좌석 순서를 섞을 뿐이며, 개인전에서도 좌석 순서 =
턴 순서다. 즉 이 절이 세운 기준을 이 절의 첫 구현이 어겼다. `TEAMS` 가 실제로 뜻하는
것은 "이 게임에 팀이 있는가"이고, 가르는 것은 **라벨**뿐이다.

교훈은 §0 과 같은 종류다 — 포트를 뽑았다고 게임 결합이 사라지지는 않는다. **어디에 남았는지
측정해야 보인다.** 이번엔 실제로 앱을 띄워 스컬킹 방을 만들어 보고서야 드러났다.

### 리매치도 게임이 선언한다 (D-122) — *D-153 에서 폐기: 리매치 대신 모든 게임의 사람만의 방이 대기실로 돌아간다*

같은 종류의 결합이 매치 종료 처리에도 남아 있었다. `MatchProgressService` 는 "사람만의 매치면
방을 IN_GAME 으로 둔다"(D-82 리매치 대기)를 **모든 게임**에 적용했는데, 리매치('한 판 더')는
티츄의 내기 테이블 장치다. 스컬킹 사람끼리 방은 끝난 뒤에도 IN_GAME 으로 남았고, 그 상태의
'나가기'는 탈주 판정(해당 없음) → 일반 leave 로 흘러 좌석 목록을 당겼다 — 남은 사람의 종료
패널 이름이 밀리고, resync 가 다른 좌석의 비공개 뷰를 줄 수 있었다.

```java
default boolean supportsRematch() { return false; }
```

- **기본 false(옵트인)** — `supportedRoomOptions()` 와 같은 원칙. 새 게임은 한 줄도 안 쓰면
  정상 종료한 방이 FINISHED 로 넘어간다. 티츄만 `true`.
- 인프라 규칙: 매치가 끝나면 방을 FINISHED 로 만든다. **예외는 하나** — 봇이 없고 게임이
  리매치를 지원할 때(리매치 대기, IN_GAME 유지).
- `RoomOption` 이 아니라 메서드인 이유: 방 생성 때 사용자가 고르는 설정이 아니라 게임 구조의
  성질이다. UI 게이팅도 없다(리매치 버튼은 지원 게임의 게임판에만 있다).

### 방 만들기의 처음 선택도 게임이 선언한다 (D-130)

인원·턴 제한은 모든 게임에 통하는 설정이라 `RoomOption` 이 아니다(위). 그런데 **처음에 무엇을 골라 둘지**는 게임마다
다르다 — 원카드는 2~6인인데 모달이 최대(6)를 골라 두어 친구 넷이 기본값으로 만든 방이 시작하지 않았고, 턴 제한 '끔'이면
자리를 비운 한 명이 판을 무기한 멈췄다.

```java
default int defaultPlayers()     { return maxPlayers(); }
default int defaultTurnSeconds() { return 0; }
```

- **기본이 지금까지의 동작**(최대 인원·끔)이라 티츄·스컬킹은 한 줄도 바꾸지 않았다. 원카드만 4·30.
- 카탈로그(`GET /api/games`)에 실려 모달의 **처음 선택**이 된다 — 사용자는 바꿀 수 있고, 서버는 범위만 검증한다.
  **D-131 부터 `POST /api/rooms` 에서 `capacity`·`turnSeconds` 를 생략한 요청의 서버 기본값도 이 값이다**
  (`RoomService.createRoom` — `RoomServiceCreateDefaultsTest`). 기본 구현이 maxPlayers·0 이라 티츄·스컬킹은 예전
  생략 기본과 같다.

### 시간이 지나면 일어나는 전이도 게임이 선언한다 (D-128)

원카드의 "원카드!/잡기!" 경쟁 창은 아무도 행동하지 않아도 상태가 바뀐다 — 추첨된 봇이 누를 시각이 오면
봇이 누르고, 3초가 지나면 창이 닫힌다. 포트에는 이런 전이가 없었고 시간은 방의 턴 제한에만 묶여 있었다.
봇 루프·턴 타임아웃을 재활용하면 턴 제한을 끈 방에서 창이 닫히지 않으므로 엔진이 타이머를 선언하는
선택형 확장을 두었다.

```java
default Optional<Duration> timer(GameState state) { return Optional.empty(); }
default Optional<Result> onTimer(GameState state) { return Optional.empty(); }
```

- **기본 없음(옵트인)** — 티츄·스컬킹은 한 줄도 바꾸지 않았다.
- **무장**: `TurnTimeoutScheduler.onTurnAdvanced` 가 턴 데드라인과 **같은 세대 번호**로
  `deadlines:game`(member `{roomId}#{generation}`)에 건다. 액션·봇·타임아웃·탈주·라운드 시작이 이미 이
  메서드를 부르므로 호출 지점은 늘지 않고, 턴 제한을 끈 방에서도 걸린다. 대가는 진행마다 상태 조회 1회
  (Redis GET) — 타이머가 없는 게임도 같다.
- **남은 시간**을 돌려준다. 재무장해도 처음부터 다시 세지 않게, 게임은 시작 시각을 상태에 두고 자기
  시계로 계산한다(원카드: 창 연 시각 + 가장 빠른 봇 반응 또는 창 길이).
- **발화**: `EngineTimerScheduler` 가 턴 타임아웃과 같은 가드를 거친다 — 세대 → IN_GAME → 락(실패 시 200ms
  재시도) → 락 안 재확인(세대·방 상태). 적용만 `timeoutAction` 대신 `onTimer` 이고, 결과는 다른 진행과 같은
  길(저장 → `advance` → 브로드캐스트 → 봇·타이머 재무장)을 탄다.
- **락 안 `timer` 재확인 — 마지막 방어선**: 인프라는 락을 쥔 채 상태를 읽고 `timer(state)` 를 **다시 물어 0
  이하일 때만** `onTimer` 를 부른다. 비어 있으면(낡은 발화) 아무것도 하지 않고, 0 보다 크면 같은 세대로 그 시간
  뒤에 다시 걸고 끝낸다(저장·브로드캐스트·재무장 없음). 만기라고 답했는데 `onTimer` 가 비어 있으면(계약 위반)
  WARN 만 남기고 버린다.
  - 왜 필요한가: 세대 번호는 대부분의 낡은 발화를 거르지만, 세대를 올리는 쪽과 상태를 바꾸는 쪽이 같은 락 안에 있지 않으면
    **락 해제와 세대 상승 사이에 틈**이 생긴다. 그 틈에 만기된 옛 타이머는 락 앞뒤의 세대 검사를 모두 통과해 이미 넘어간
    상태를 만난다. 세대 번호가 "상태 무변경"을 보장한다고 믿으면 안 된다. *(D-131: 방 액션 락을 쥔 진행 경로 — 액션·봇·
    턴 타임아웃·엔진 타이머 발화·매치가 이어지는 탈주 — 는 이제 락을 풀기 전에 재무장한다(resync 의 남은 턴 시간이 같은 락
    안에서 읽혀야 해서). 락 밖에서 재무장하는 매치 시작(라운드 스타터)에는 틈이 남고, 이 재확인은 그대로 마지막 방어선이다.)*
  - 게임에 주는 뜻: 불린 시점의 상태는 만기라서 `onTimer` 가 시각을 다시 볼 필요는 없다. 대신 `timer` 는 같은
    상태에서 시계가 흐르면 줄어 결국 0 이하가 돼야 하고, 0 이하일 때 `onTimer` 는 전이를 줘야 한다.
  - *처음 설계는 "세대 번호가 보장하므로 `onTimer` 는 시각을 다시 보지 않는다"였다 — S3 최종 리뷰에서 바로잡았다.
    턴 타임아웃에도 같은 틈이 원래부터 있었다 — D-131 에서 락을 쥔 진행 경로의 재무장을 락 안으로 옮겨 닫았다.*
- **해상도**: 폴링 주기(`mirboard.scheduling.poll-interval-millis`, 기본 250ms) 단위다. 타이머는 만기 시각에
  정확히 깨지 않고 다음 폴링에서 발견되며(최대 한 주기), 락 경합이면 200ms 뒤 재시도한다. 폴러가 단일 스레드로
  종류(`turn`·`game`·`desertion`)를 차례로 처리하므로 다른 핸들러가 느리면(탈주 확정의 락 대기는 최대 약 3초)
  그만큼 더 늦는다. 게임은 이 지연을 설정으로 흡수한다 — 원카드는 창 끝·봇 시각 직후에 처리된 누름도 인정한다.
- **취소**: 세대가 오르면(누가 행동함) 두 종류가 함께 지워진다. `cancel()`(D-122)도 마찬가지다.
- **턴의 남은 시간(D-131)** — 포트가 아니라 인프라가 답한다: resync 응답의 `turnRemainingMs` 는 지금 세대의
  `deadlines:turn` 항목 − 지금이다(`TurnTimeoutScheduler.turnRemaining`). 게임에 새로 요구하는 것은 `pendingSeats` 뿐이다 —
  비어 있으면(원카드 경쟁 창·끝난 매치) 걸린 데드라인이 있어도 발화해 봐야 아무 일이 없으므로 null 이다.
  대기 좌석이 여럿인 단계(티츄 Dealing·Passing, 스컬킹 BIDDING)에서는 **첫 대기 좌석(`pendingSeat`)의 자동 처리까지**
  남은 시간이지 각 좌석의 남은 시간이 아니다 — 시간 초과는 그 좌석 하나만 처리하고 처음부터 다시 걸며, 다른 좌석의
  행동도 처음부터 다시 건다.
- **왜 `GameAction` 이 아니라 `Result` 인가**: 액션은 클라 JSON 에서 역직렬화된다. "창 닫기" 같은 시스템
  전이를 그 계층에 두면 클라가 위조해 보낼 수 있다.
- 시간 전이가 진행 중인 동안 게임은 `pendingSeats` 를 비워 두면 된다(원카드 경쟁 창) — 봇 루프와 턴
  타이머가 끼어들지 않고, 시간 진행은 엔진 타이머 하나가 맡는다.
- **사라진 타이머는 진행 킥이 다시 건다 (D-130).** 이 타이머가 무장 실패·발화 중 실패·저장~무장 사이 종료로
  사라지면 창을 닫을 주체가 없다(봇 루프·턴 타이머는 창 동안 아무것도 안 한다). 그래서 클라가 방을 다시 볼 때(resync 응답
  뒤·게임 토픽 구독 — 그 방의 참가자·관전자일 때, `infra.bot.GameProgressKick`) 인프라가 `timer(state)` 를 물어 이 세대의
  `deadlines:game` 항목을 남은 시간으로 건다 — 그 member 가 **없을 때만 더한다**(`DeadlineQueue.scheduleIfAbsent`, ZADD NX).
  확인과 쓰기가 원자라 그 사이 걸린 정상 무장(재시도·미만기 재무장)을 락 없이 읽은 낡은 남은 시간으로 덮지 않는다. 같은
  킥이 봇 차례인데 이 인스턴스에 봇 루프가 없으면(재기동) 루프도 다시 건다. 게임에 새로 요구하는 것은 없다 — 위 계약(남은
  시간·락 안 재확인)이 그대로 지킨다. 킥은 `onTurnAdvanced` 를 부르지 않는다(세대를 올리면 resync 반복으로 턴 데드라인을
  미룰 수 있다).

### 한 차례가 여러 액션인 게임은 차례 전체 마감을 엔진 타이머로 건다 (D-183)

요트가 실제로 깬 가정은 §1 표가 예상한 곳(비공개 뷰·12칸 종료·조합 폭발 — 셋 다 이미 포트에 있었다)이 아니라 표에 없던
**"액션 하나 = 차례 하나"**였다. 진행 경로는 성공한 액션마다 `onTurnAdvanced` 로 세대를 올리고 턴 데드라인을 `turnSeconds` 로
처음부터 다시 건다. 티츄·스컬킹·원카드는 차례당 액션이 하나라 문제가 없지만, 요트는 굴림·홀드·기록이 한 차례라 홀드를 바꾸기만
해도 제한이 끝없이 늘어난다.

- **포트 변경은 `GameContext.turnSeconds` 하나다**(방 설정 — 0 = 끔, 매치 중 불변). `GameEngineProvider.forRoom` 이 `room.turnSeconds()`
  를 싣는다. 예전 5필드 생성자는 0 으로 남겼고 다른 게임은 이 값을 무시한다 — 동작 무변경. 인프라는 그대로다.
- 게임은 차례 시작 시각을 상태에 두고 ⑦ `timer(state)` = 차례 시작 + `turnSeconds` − 지금을 돌려준다. 만기면 `onTimer` 가 자동 처리한다
  (요트: 필요하면 한 번 굴리고 최고점 칸에 기록). 재무장해도 처음부터 세지 않는다(⑦ 계약 그대로).
- 인프라 턴 데드라인(마지막 액션 + T)은 그대로 걸리지만 늘 차례 마감(차례 시작 + T)보다 늦어, 엔진 타이머가 먼저 발화해 세대를 올리면
  지워진다. 그쪽 `timeoutAction` 은 폴백으로만 남는다.
- resync 의 바깥 `turnRemainingMs`(인프라 — 마지막 액션 기준)는 이런 게임에서 차례 남은 시간과 다르다. 요트는 공개 뷰에 엔진이 계산한
  `turnRemainingMs` 를 싣는다. 인프라 쪽을 고치지 않은 이유: 다른 세 게임은 둘이 같다.
- 포격 기획처럼 이동을 발사 액션에 실어 "한 차례 = 한 액션"으로 맞추는 방법도 있다. 요트는 홀드를 실시간으로 보여 주기로 해
  (D-181) 그 길을 못 쓴다.
- 오목 S3a(D-176)의 `options` 뒤에 붙였다 — 레코드는 `(…, botSeats, options, turnSeconds)`. D-176 이 남긴 규칙대로 기존 짧은 생성자
  (5필드·`options` 까지 6필드·2필드)는 그대로 두고 `turnSeconds` 에 0(끔)을 넣는다.

## 3. 인원 가변 (스컬킹 2~8 결정의 파급) — **구현 완료 (D-99 / S2)**

스컬킹을 **2~8인**(BGG 추천 4~6)으로 확정하면서 인원 가변이 **필수**가 됐다.
구현 전 `RoomService.createRoom` 은 `capacity = def.maxPlayers()` 로 고정했고, 이대로면
스컬킹 방은 항상 8인이 되어 4인 게임을 만들 수 없었다.

**변경 범위** (계약 슬라이스 — 서버 DTO + 클라 미러 + docs 한 커밋):
- `POST /api/rooms` 에 `capacity` 선택 필드. 미지정 시 `def.maxPlayers()`(현행 호환). *(D-131: 미지정 시
  `def.defaultPlayers()` — 기본 구현이 maxPlayers 라 원카드만 4.)*
- 검증: `def.minPlayers() <= capacity <= def.maxPlayers()`. 위반 시 `INVALID_CAPACITY`.
- 클라 방 만들기 모달에 인원 선택(게임이 가변일 때만 노출).
- 티츄는 `min=max=4` 라 UI 가 안 바뀐다 — **기존 동작 무변경**.

**구현 후 확인된 제약**: `fillWithBots` 는 시드 봇 4명(V3)만 쓰므로 `capacity - 1 > 4`
인 방은 봇으로 채울 수 없다(`IllegalStateException`). 티츄 4인에서는 도달 불가라
지금까지 드러나지 않았고, 스컬킹 6~8인 방에서 처음 문제가 된다 — **S5 의 봇 정책
작업에 봇 풀 확장이 포함되어야 한다**. 계약(`api.md`)에 제약으로 명시해 두었다.

## 4. 검증 전략 — **실행 결과 (S1)**

포트 추출은 **게임이 하나 그대로**이므로 D-87 과 같은 "동작 무변경 리팩토링"이다.

- **안전망**: 서버 **412건 전량 그린**(69 클래스, 실패 0). `BotMatchSimulationIT`(봇 풀매치)·
  `TurnTimeoutSchedulerIT`·`TichuInvariantChecker` 가 룰 회귀를 잡는다. 추가로
  `check.sh bot-stress 5` (봇 풀매치 5회) 통과.
- **금지 준수**: 티츄 룰 코드는 한 줄도 바뀌지 않았다 — `TichuEngine`·`ActionValidator`·
  `Hand*`·`ScoreCalculator` 무변경(diff 상 `TichuEngine` 은 javadoc + `implements` 절 뿐).
- **와이어 계약 무변경 증거**: `GameStompControllerIntegrationTest` 가 실제 WebSocket 으로
  `PLAY_CARD` 프레임을 보내 `PLAYED` 브로드캐스트를 확인하고, `RoomResyncIntegrationTest` 가
  resync JSON 을 jsonPath 로 검증한다(`tableView`/`privateHand` 필드 타입이 `Object` 가
  됐지만 직렬화 결과는 동일).
- **포트가 티츄 모양으로 굳었는지는 요트에서 드러난다.** 스컬킹만으로는 검증되지 않으므로,
  스컬킹 완료 시점에 "요트를 넣는다면 어디가 막히나"를 종이로 한 번 통과시킨다.

**구현 중 걸린 함정 하나** (기록용): Spring Framework 7 의 STOMP 브로커 메시지 컨버터는
**Jackson 3** 기반이라 Jackson 2 의 `JsonNode` 를 `@Payload` 대상 타입으로 받지 못한다
(`MessageConversionException: Cannot construct instance of JsonNode`). 게임별 액션을 원본으로
받으려면 버전 중립인 `Map<String,Object>` 로 받아 우리 `ObjectMapper` 로 변환해야 한다.

## 5. 열린 질문 — **전부 결정 (S1)**

1. **`GameState` 는 마커로 확정.** 공통 필드 0개. 라운드 번호 같은 걸 요구하면 그 개념이
   없는 게임(요트=12칸 기록표)이 거짓 구현을 강요당한다. 중복이 실제로 보이면 그때 올린다.
2. **액션 역직렬화 seam = 목적지에서 방 → gameType 분기** (유력했던 안 그대로).
   `engine.actionType()` 이 대상 타입을 주고 컨트롤러가 변환한다. 목적지는 하나로 유지되어
   클라 계약은 바뀌지 않았다. 알 수 없는 판별자는 `ERROR(INVALID_ACTION)`.
3. **봇 정책은 포트 메서드 + 게임별 override 로 분리.** `botAction(state, seat, random)` 의
   기본 구현이 "합법 액션 균등 분포"다. 세 게임 모두 이를 결정적 휴리스틱으로 override 한다 —
   티츄는 `HeuristicBotPolicy`(D-118), 스컬킹은 `SkullKingBotPolicy`(D-119 → D-230 상대 모델 몬테카를로 — 표본 시드를 뷰에서 만든다), 원카드는 `OneCardBotPolicy`(D-128 — 경쟁 창의
   누름은 정책이 아니라 창을 열 때 추첨한 반응 시간으로 엔진 타이머가 한다). 셋 다 공개 정보만
   보고 `random` 인자를 쓰지 않으므로 같은 상태면 같은 수다(`rules-tichu.md` §16 ·
   `rules-skullking.md` §16). 시드 `Random` 은 스케줄러가 계속 보유하므로 포트 기본 봇을 쓰는
   새 게임에는 `mirboard.bot.seed` 재현성이 그대로 남는다.
   **새 게임 권장 패턴**: 봇 정책은 공개 정보만 담은 뷰를 받아 `legalActions` 의 원소를
   고르고, 어댑터는 정책이 예외·null·비합법 액션을 내면 ERROR 로그 후 `timeoutAction` 으로
   폴백한다 — 턴 제한 0 방에선 스케줄러가 재시도하지 않아 정책 버그가 방 정지로 이어진다.
   `timeoutAction`(사람·탈주 좌석 대리)은 봇 정책과 섞지 않는다.

### 턴 제한 선택지·상대 전적도 게임이 선언한다 (D-154)

- `GameDefinition.turnSecondsChoices()` — 방 만들기 모달의 턴 제한 선택지(기본 끔·30·60·90). 원카드는 끔·5·10·20·30.
  `defaultTurnSeconds()` 는 이 안이어야 한다(`RoomCreationDefaultsTest`). 서버는 선택지 밖 값을 거절하지 않는다(화면 목록).
- `HeadToHeadSource`(`domain.game.core`) — 게임이 자기 매치 기록에서 "나 대 상대" 승·패·무를 센다. `@Component` 로 두면
  `HeadToHeadService` 가 모은다(게스트·봇 거르기·이름 붙이기는 모으는 쪽). 무엇을 이긴 것으로 볼지는 게임이 정한다.
  - 티츄: 상대 팀만 센다(내 팀 승패).
  - 스컬킹: 최종 점수로 센다.
  - 원카드: 최종 순위로 센다.
- `BestScoreSource`(`domain.game.core`, D-201) — 점수를 겨루는 게임이 자기 기록에서 한 사람의 개인 최고 점수를 낸다. 같은 모양으로
  `BestScoreService` 가 모으고(게스트·봇은 빈 결과) `GET /api/users/{id}/stats` 의 `games[].bestScore` 로 싣는다. 어떤 판을 셀지는 게임이
  정한다 — 요트: 혼자 끝까지 친 판(`yacht_solo_scores`, 승패·전적은 없음) + 탈주 없이 `FINISHED` 로 끝난 여럿 판의 총점. 승패만 있는 게임은 두지 않는다.

### 방 설정이 엔진에 닿는다 — 게임 중립 확장 3건 (D-176)

첫 쓰임은 오목(모드 1:1/2:2·룰·봇 난이도, `docs/plans/omok.md` §3.3)이지만 세 확장 모두 게임 이름이 없고, **기본값이 지금 동작**이라
티츄·스컬킹·원카드는 한 줄도 바뀌지 않았다. 인프라에 게임 이름 분기도 늘지 않았다(`GameEngineProvider` 한 곳만 바뀌었다).

| # | 확장 | 기본 | 어디서 쓰나 |
| --- | --- | --- | --- |
| E1 | **맵 방 옵션** — 허용값·기본값을 가진 `RoomOption` 상수(`valued()` — `TEAM_MODE`·`OMOK_RULE`·`BOT_LEVEL`·D-215 `MATCH_LENGTH`·D-202 `TURN_MODE`)는 `Room.options`(`Map<RoomOption,String>`, Redis `room:{id}` 해시 `opt:<NAME>`)에 둔다 | 옵션 없음 | 방 만들기(선언한 옵션의 기본값을 `room_create.lua` 같은 원자 구간에서 씀), `PUT /settings` 의 `options`, 카탈로그 `roomOptionChoices` |
| E2 | **정원·시작 인원** — `GameDefinition.capacityFor(options)`·`playersToStart(options)` | `maxPlayers()`·`minPlayers()` | 방 만들기 정원 상한, 설정 변경의 정원 재계산, 대기실 복귀 정원(`room_return_waiting.lua`), 방장 시작(`room_start_now.lua` 의 최소 인원 — Lua 무변경). 봇 채우기는 방 정원을 따르므로 자동 |
| E3 | **엔진에 옵션 전달** — `GameContext.options` | 빈 맵 | `GameEngineProvider.forRoom` 이 `RoomService.effectiveOptions(def, room)` 를 싣는다 |

- **늘 값이 있다**: 정원·시작 인원·엔진이 보는 옵션은 모두 `RoomOption.resolve(선언, 저장값)` 결과다 — 게임이 선언한 맵 옵션만, 저장값이
  허용값이면 그 값, 없거나 모르는 값이면 기본값. 그래서 `capacityFor`·엔진은 `options.get(...)` 이 null 인 경우를 다루지 않아도 된다.
  저장값은 그대로 두므로(`Room.options`) 선언을 바꿔도 옛 방이 깨지지 않는다.
- **검증(D-106 과 같은 규칙)**: `PUT /settings` 의 `options` 에서 선언하지 않은 옵션은 기본값이면 무시, 아니면 `UNSUPPORTED_ROOM_OPTION`.
  모르는 이름·허용값 밖·자기 필드가 있는 예전 옵션(`TARGET_SCORE` 등)은 `INVALID_ROOM_SETTING`.
- **정원이 바뀌는 쓰기**: 바꾼 옵션으로 `capacityFor` 가 달라지면 `capacity` 도 같이 쓴다. 지금 앉은 인원이 새 정원보다 많으면
  `TOO_MANY_PLAYERS`(409) — 서비스가 먼저 보고, `room_update_settings.lua` 가 같은 원자 구간에서 `LLEN players ≤ 새 정원` 을 다시 본다
  (확인 뒤 누가 앉는 경합). `room_join.lua` 도 같은 해시의 `capacity` 를 원자적으로 읽으므로 capacity 위반 0 이 유지된다. 준비 표시는 풀지 않는다.
- `capacityFor` 는 `minPlayers()..maxPlayers()` 안이어야 한다 — 밖이면 서버가 정의 버그로 바로 던진다. `playersToStart` 를 정원과 같게
  돌려주면 "정확히 그 인원"이다(`room_start_now.lua` 는 "그 이상"만 보지만 정원을 넘을 수는 없다).
- D-196 — 카탈로그의 `playerCounts`(할 수 있는 인원)는 `GameDefinition.playerCounts()` 기본 구현이 선언한 맵 옵션의 값 조합마다
  `playersToStart..capacityFor` 를 모은 합집합이다(오목 [2, 4]). 맵 옵션이 없으면 `minPlayers..maxPlayers` 라 재정의할 일이 거의 없다.
- 원카드 버튼 두 설정(`raceButtonSize`·`raceButtonVisibility`)은 아직 자기 필드다 — 맵으로 옮기는 정리는 후속(동작 무변경 리팩토링).
- `GameContext` 에 방 설정을 하나 더 실을 때(요트의 턴 제한 제안 등)는 레코드 끝에 붙이고 짧은 생성자를 남긴다(지금 5인자·2인자 생성자가 그 모양).
- 검증: `RoomOptionTest`·`RoomServiceRoomOptionsTest`·`GameEngineProviderOptionsTest`(단위, 가짜 게임 `TeamModeTestGame` — 정원 2↔4,
  3명 시작 거절, 인원 초과 축소 거절, 선언한 옵션만 전달), `RoomOptionsIntegrationTest`(실 Redis·Lua).
- **라운드 시작에도 같은 값(오목 S3b)**: `GameStartingEvent.options` — 라운드 스타터는 엔진이 아니라 시작 이벤트를 받으므로, `RoomService` 가
  발행할 때 `effectiveOptions(def, room)` 를 싣는다(4인자 생성자는 빈 맵으로 남겼다). 오목 라운드 스타터가 모드·룰을 여기서 읽는다.

### 상태를 바꾸지 않는 실시간 미리보기 ⑧ `preview` (D-229) — 구현 씨앗포 S6a · 얏추 D-268

씨앗포 S6(실시간 이동·조준 공유)에서 처음 필요해졌다. 확정하는 이동은 새 엔진 액션 + 차례 전체 마감(D-183)으로 포트 변경 없이 풀고(D-228),
쏘기 전까지 바뀌는 포신 각도·탄만 이 확장으로 흘린다. 설계서 `docs/plans/artillery-s6.md` §5.

```java
default Optional<GameEvent> preview(GameState state, int seat, GameAction input) { return Optional.empty(); }
```

- **기본 없음(옵트인)** — ⑦ 엔진 타이머와 같은 방식. override 하지 않은 게임은 미리보기 프레임이 전부 버려진다.
- 인프라: 게임 중립 `infra.ws.GamePreviewController`(`/app/room/{id}/preview`) — `IN_GAME`·좌석 확인 → `actionType()` 으로 역직렬화 →
  `loadState()`(**락 없음**) → `preview` → 있으면 방 토픽으로 방송. 락·저장·`onTurnAdvanced`·봇 스케줄을 하지 않으므로 `BUSY`·턴 시계와 무관하다.
  레이트리밋 버킷 `game-preview`(넘치면 조용히 버림).
- 계약: ① 순수(상태·저장소 무변경, 락 밖이라 낡은 상태를 볼 수 있다) ② 가볍다(차례 주인이 초당 수 번) ③ 돌려주는 이벤트는 **공개·순번 없음** —
  어기면 인프라가 WARN 후 버린다 ④ 받아들일 수 없는 입력은 empty(거절 `ERROR` 없음) ⑤ 미리보기 입력 타입은 `actionType()` 의 한 갈래 —
  `apply` 는 `INVALID_ACTION` 으로 거절, `legalActions`·봇에 나오지 않는다.
- 클라 훅 변경 없음 — 순번 없는 공개 이벤트는 `judgeSeq` 가 `unsequenced` 로 sink 에 넘긴다(D-124). 받는 쪽이 낡은 프레임을 거르는 기준은 게임이
  이벤트에 싣는다(씨앗포: 차례 번호 + 보낸 쪽 번호 `n`).
- **다른 게임 영향 0 의 근거(S6a 실행)**: 포트 기본 empty(`GameEnginePreviewDefaultTest.the_default_preview_is_empty`), 여섯 어댑터에 `preview`
  override 없음(같은 테스트가 리플렉션으로 확인), 컨트롤러는 empty 면 방송하지 않음·계약 위반 이벤트는 WARN 후 버림(`GamePreviewControllerTest`),
  인프라 `grep -rniE 'artillery|씨앗포' server/src/main/java/com/mirboard/infra` 0건, 서버 전체 스위트 회귀 0. 실제 STOMP 경로는
  `ArtilleryLiveMoveIT`. 씨앗포 이동은 ⑦ + `GameContext.turnSeconds`(D-183 과 같은 쓰임) — 이동 쪽 포트 변경은 0 이다.
- **두 번째 구현 — 얏추 컵 흔들기(D-268)**: 포트·인프라 변경 0(컨트롤러·버킷·클라 훅 `sendPreview` 그대로). 얏추 `actionType()` 에 미리보기 입력
  `YachtAction.Shake(shaking)` 한 갈래를 더하고(`apply` 는 `INVALID_ACTION`, `legalActions`·봇에 없음 — 계약 ⑤), 어댑터 `preview` 가 순수 엔진
  `YachtEngine.preview` 에 넘긴다 — 시작·유지는 **지금 굴릴 수 있는 좌석**만(둘 차례·굴림 남음·다 고정 아님, 모두 같이면 각자), 멈춤은 살아 있는
  좌석이면 받는다(굴린 직후의 멈춤이 버려져 남의 화면에 컵이 남지 않게). 이벤트 `YachtEvent.DiceShaking(seat, shaking)` 은 `sequenced() == false`
  — 요트에서 순번을 쓰지 않는 유일한 이벤트다. 인바운드 채널은 수신 순서를 보장하지 않으므로(전역
  `setPreserveReceiveOrder` 는 다른 게임에 영향이 있어 쓰지 않는다) 씨앗포처럼 보낸 쪽 번호 `n` 을 싣는다 — 서버는 범위만 보고 그대로 돌려주고
  (상태 없음), 받는 쪽이 좌석별 마지막 `n` 보다 낡은 프레임을 버리며 그 좌석의 굴림 직후 0.8초는 시작을 받지 않는다. 놓친 멈춤은
  **유지 프레임(1.5초) + 무소식 만기(4초)** 가 메운다(그 좌석의 굴림·기록·차례 넘어감·resync 도 지운다). 근거:
  `YachtShakePreviewTest`·`YachtGameEngineTest.preview_…`, `GameEnginePreviewDefaultTest`(이제 씨앗포·얏추 둘만 override — 나머지 다섯은 그대로),
  인프라 `grep -rniE 'yacht|요트' server/src/main/java/com/mirboard/infra` 0건 유지.

### 비공개 뷰 없음 — 오목에서 실증 (D-175)

§1 설계 판단 2 의 "요트는 empty" 가 오목에서 처음 실제 경로를 탔다. `OmokGameEngine.privateView` 는 늘 `Optional.empty()` 이고,
`RoomController.resync` 의 `.orElse(null)` 로 `privateHand` 가 응답에서 빠진다(`OmokServerFlowIT.resync_carries_the_public_board_and_no_private_hand`).
비공개 이벤트도 `ERROR` 뿐이라 게임 이벤트는 전부 순번을 쓴다. 포트 메서드는 하나도 늘지 않았다 — 오목이 쓴 포트 밖 변경은 D-176 확장 3건과
시작 이벤트의 옵션 하나뿐이다(`grep -rni omok server/src/main/java/com/mirboard/infra` → 0건).

