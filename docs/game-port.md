# GameEngine 포트 설계 (D-97)

> 상태: **설계 확정 — 구현 대기** · 작성 2026-07-30
> 이 문서는 **계약 정본**이다. 포트를 바꾸면 여기를 먼저 고친다.
> 구현 순서와 세션 분할은 `docs/plans/multi-game-sessions.md`.

## 0. 현재 상태 (착수 전 사실)

`CLAUDE.md` 와 D-06/D-11 은 "새 게임 = 패키지 + `GameDefinition` Bean 등록"이라고 적고
있지만, **카탈로그(`GET /api/games`)까지만 사실**이다.

| 확인 | 실제 |
| --- | --- |
| `GameEngine` / `GameAction` / `GameEvent` | 메서드 **0개** (빈 마커) |
| `GameDefinition.newEngine()` | 호출부 **0건** (죽은 코드) |
| infra 의 Tichu 직접 참조 | **10파일 · 123회** |
| `GameStompController` | `@Payload TichuAction` 으로 **타입 고정** |

문서가 코드보다 앞서 있다. 2단계(포트 추출)가 끝나야 CLAUDE.md 문구가 참이 된다.

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
public interface GameEngine {
    /** 액션 적용. 룰 위반은 GameActionRejectedException. */
    Result apply(GameState state, int seat, GameAction action);

    /** 새 매치/라운드 시작 상태. seatCount 는 방 인원(가변). */
    GameState initialState(int seatCount, long seed);

    /** 공개 뷰 — 모든 참가자·관전자가 본다. */
    Object publicView(GameState state);

    /** 본인 전용 뷰. 비공개 상태가 없는 게임(요트)은 Optional.empty(). */
    Optional<Object> privateView(GameState state, int seat);

    /** 클라 분기용 단계 이름. */
    String phaseName(GameState state);

    /** 지금 행동을 기다리는 좌석. 없으면 -1 (자동 진행 대상 없음). */
    int pendingSeat(GameState state);

    /** 라운드가 끝났는가 — 끝났으면 좌석별 점수. */
    Optional<Map<Integer, Integer>> roundScores(GameState state);

    /** 매치가 끝났는가. 티츄=목표점수, 스컬킹=10라운드, 요트=12칸. */
    boolean isMatchOver(GameState state, MatchProgress progress);

    /** 봇·타임아웃용. 비어 있으면 그 좌석은 지금 할 수 있는 게 없다. */
    List<GameAction> legalActions(GameState state, int seat);

    /** 타임아웃 시 적용할 안전 액션. null 이면 아무것도 안 함. */
    GameAction timeoutAction(GameState state, int seat);

    record Result(GameState newState, List<GameEvent> events) {}
}
```

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
| 트릭·리드수트 | 트릭테이킹 계열만의 개념. 스컬킹은 **티츄와 공유하는 내부 모듈**로 뽑되 포트는 아님 |

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

## 4. 검증 전략

포트 추출은 **게임이 하나 그대로**이므로 D-87 과 같은 "동작 무변경 리팩토링"이다.

- **안전망**: 서버 391건 전량. 특히 `BotMatchSimulationIT`(풀매치)·`TurnTimeoutSchedulerIT`·
  `TichuInvariantChecker` 가 룰 회귀를 잡는다.
- **금지**: 포트 추출 커밋에서 티츄 룰을 손대지 않는다. 룰 변경이 섞이면 이후 회귀의
  원인 분리가 불가능해진다.
- **포트가 티츄 모양으로 굳었는지는 요트에서 드러난다.** 스컬킹만으로는 검증되지 않으므로,
  스컬킹 완료 시점에 "요트를 넣는다면 어디가 막히나"를 종이로 한 번 통과시킨다.

## 5. 열린 질문 (구현 중 결정)

1. `GameState` 를 마커 인터페이스로 둘지, 공통 필드(라운드 번호 등)를 요구할지.
   → 마커로 시작하고 중복이 보이면 올린다. 처음부터 공통 필드를 강요하면 요트가 깨진다.
2. 액션 역직렬화 seam — `@JsonTypeInfo` 판별자를 게임별로 어떻게 고를지.
   목적지(`/app/room/{roomId}/action`)에서 방 → gameType 을 조회해 분기하는 방식이 유력.
3. 봇 정책의 게임별 분리 — `RandomBotPolicy` 는 티츄 액션에 묶여 있다.
