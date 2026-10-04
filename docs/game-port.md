# GameEngine 포트 설계 (D-97 설계 · D-98 구현)

> 상태: **구현 완료** (S1, D-98) · 설계 2026-07-30 · 반영 2026-07-30 · 엔진 타이머 추가 D-128(2026-10-04)
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

지금 인게임이 실제로 요구하는 것은 6가지다. 각각의 현재 구현과 "요트가 깨는 지점"을
같이 적는다 — **포트는 두 번째 게임이 아니라 세 번째 게임에서 검증**되기 때문이다.

| # | 책임 | 현재(티츄) | 스컬킹 | 요트가 깨는 지점 |
| --- | --- | --- | --- | --- |
| 1 | 액션 적용 | `TichuEngine.apply(state, seat, action) → (newState, events)` | 동일 | 동일 |
| 2 | 상태 직렬화 | `TichuGameStateStore` (JSON→Redis) | 동일 | 동일 |
| 3 | 공개 뷰 | `TichuStateMapper.toTableView` | 동일 | 동일 |
| 3' | **비공개 뷰** | `toPrivateHand(state, seat)` | 동일(손패) | **없음** — 점수판이 공개 → Optional |
| 4 | 단계 이름 | `phaseName(state)` | 입찰/플레이 | 굴림/기록 |
| 5 | 라운드·매치 진행 | `MatchProgressService.onRoundEnd` + `TichuMatchState.isMatchOver` | **10라운드 고정** | **12칸 채우면 종료** |
| 6 | 합법 액션 | `LegalActionEnumerator.enumerate` + `TimeoutActionPolicy.choose` | 동일 | 조합 폭발(주사위 고정 2^5 × 남은 칸) |

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

    // ⑤ 라운드 · 매치 진행
    Advance advance(GameState newState, List<GameEvent> outbound);
    DesertOutcome desert(int seat, long deserterUserId, List<GameEvent> outbound);

    // ⑦ 엔진 타이머 (D-128) — 기본 없음. 시간이 지나면 저절로 일어나는 전이(§2)
    default Optional<Duration> timer(GameState state) { return Optional.empty(); }  // 지금부터 남은 시간
    default Optional<Result> onTimer(GameState state) { return Optional.empty(); }  // 만료 시 전이

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

`GameState` · `GameAction` 은 마커, `GameEvent` 는 `envelopeType()` + `privateSeat()` 만
노출한다(브로드캐스터가 게임을 모른 채 라우팅할 최소치). `GameContext` 는
`(roomId, playerIds, targetScore, stake, botSeats)` — 방 설정이 엔진에 들어오는 유일한 창구다.

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

### 매치 기록기 계약 (D-115·D-117)

ELO·전적은 포트 밖이지만, 새 게임의 매치 기록기(`MatchResultRecorder`·`SkullKingMatchRecorder`
같은, 게임별 매치 종료 이벤트 리스너)는 다음 두 가지를 지켜야 한다.

1. **ELO 적용 여부는 `RatedMatchPolicy.eloApplies(playerIds)` 하나로 판정한다.** 봇(D-71)이나
   게스트(D-117)가 한 명이라도 낀 매치는 전원 미적용이다(승패·탈주는 기록). 조건을 기록기마다
   따로 쓰면 한쪽만 고쳐진다 — 실제로 D-117 전에는 두 기록기에 `hasBots` 가 각각 있었다.
2. **비봇 참가자마다 `user_game_stats` 행을 남긴다**(`UserGameStatsService.record`, ELO 미적용이면
   `newRating=null`). 게스트 정리(`GuestAccountSweeper`)가 이 행을 "매치를 끝낸 증거"로 쓴다.
   참가자 테이블에만 쓰고 stats 를 빠뜨리면, 그 게스트는 48h 뒤 정리 대상이 됐다가 참가자 FK
   때문에 삭제가 매번 실패한다(그 행만 건너뛰고 warn — 정리 자체는 커서로 계속 진행).

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

### 리매치도 게임이 선언한다 (D-122)

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
  재시도) → 락 안 재확인. 적용만 `timeoutAction` 대신 `onTimer` 이고, 결과는 다른 진행과 같은 길(저장 →
  `advance` → 브로드캐스트 → 봇·타이머 재무장)을 탄다. 세대 번호가 "타이머를 건 뒤 상태 무변경"을
  보장하므로 `onTimer` 는 시각을 다시 보지 않는다.
- **취소**: 세대가 오르면(누가 행동함) 두 종류가 함께 지워진다. `cancel()`(D-122)도 마찬가지다.
- **왜 `GameAction` 이 아니라 `Result` 인가**: 액션은 클라 JSON 에서 역직렬화된다. "창 닫기" 같은 시스템
  전이를 그 계층에 두면 클라가 위조해 보낼 수 있다.
- 시간 전이가 진행 중인 동안 게임은 `pendingSeats` 를 비워 두면 된다(원카드 경쟁 창) — 봇 루프와 턴
  타이머가 끼어들지 않고, 시간 진행은 엔진 타이머 하나가 맡는다.

## 3. 인원 가변 (스컬킹 2~8 결정의 파급) — **구현 완료 (D-99 / S2)**

스컬킹을 **2~8인**(BGG 추천 4~6)으로 확정하면서 인원 가변이 **필수**가 됐다.
구현 전 `RoomService.createRoom` 은 `capacity = def.maxPlayers()` 로 고정했고, 이대로면
스컬킹 방은 항상 8인이 되어 4인 게임을 만들 수 없었다.

**변경 범위** (계약 슬라이스 — 서버 DTO + 클라 미러 + docs 한 커밋):
- `POST /api/rooms` 에 `capacity` 선택 필드. 미지정 시 `def.maxPlayers()`(현행 호환).
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
   기본 구현이 "합법 액션 균등 분포"다. 두 게임 모두 이를 결정적 휴리스틱으로 override 한다 —
   티츄는 `HeuristicBotPolicy`(D-118), 스컬킹은 `SkullKingBotPolicy`(D-119). 둘 다 공개 정보만
   보고 `random` 인자를 쓰지 않으므로 같은 상태면 같은 수다(`rules-tichu.md` §16 ·
   `rules-skullking.md` §16). 시드 `Random` 은 스케줄러가 계속 보유하므로 포트 기본 봇을 쓰는
   새 게임에는 `mirboard.bot.seed` 재현성이 그대로 남는다.
   **새 게임 권장 패턴**: 봇 정책은 공개 정보만 담은 뷰를 받아 `legalActions` 의 원소를
   고르고, 어댑터는 정책이 예외·null·비합법 액션을 내면 ERROR 로그 후 `timeoutAction` 으로
   폴백한다 — 턴 제한 0 방에선 스케줄러가 재시도하지 않아 정책 버그가 방 정지로 이어진다.
   `timeoutAction`(사람·탈주 좌석 대리)은 봇 정책과 섞지 않는다.
