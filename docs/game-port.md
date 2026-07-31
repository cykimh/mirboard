# GameEngine 포트 설계 (D-97 설계 · D-98 구현)

> 상태: **구현 완료** (S1, D-98) · 설계 2026-07-30 · 반영 2026-07-30
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
| ELO | `users.rating` 단일 컬럼이라 게임별 분리는 스키마 결정(D-02 검토 필요) |
| 좌석 수 4 고정 | 가변으로 감 — §3 |
| 트릭·리드수트 | 트릭테이킹 계열만의 개념 — 포트에 없음. *(D-101 정정: 티츄 트릭 모델은 조합 기반(`Hand`·passedSeats·wish)이고 리드수트 개념이 없어 교집합 0 — 공유 모듈 없이 스컬킹 전용 `domain/game/skullking/trick/` 으로 구현됐다)* |

**칩을 뺀 결과 — infra 의 유일한 티츄 잔여**: `RoomChipService`(infra.ws)는
`TichuGameDefinition.ID` / `TichuMatchCompleted` / `Team` 을 계속 직접 참조한다. 칩 정산은
"어느 팀이 이겼는가"에 붙어 있어서 포트로 올리려면 팀 개념을 다시 끌어올려야 하고, 신규
게임은 `stake=0` 으로 시작하므로 지금 일반화하면 **쓰이지 않는 추상**이 된다. 스컬킹에
내기를 붙이는 시점에 "승자 집합"만 다루는 게임 중립 정산 이벤트를 별건으로 검토한다.

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
   기본 구현이 "합법 액션 균등 분포"이고 티츄는 `RandomBotPolicy` 로 override 한다. 시드
   `Random` 은 스케줄러가 계속 보유하므로 `mirboard.bot.seed` 재현성이 유지된다.
