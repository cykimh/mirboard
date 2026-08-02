# 케이스 스터디 — 두 번째 게임을 붙이기까지

티츄 하나만 돌아가던 시점, 인프라 **10개 파일**이 티츄를 직접 알고 있었습니다.
포트를 뽑고 두 번째 게임(스컬킹)을 붙였더니 REST/WS 컨트롤러·스케줄러·브로드캐스터·로비는
한 줄도 바뀌지 않았고, 인프라 변경은 **포트 계약 확장 1건**뿐이었습니다.

그 1건이 이 글의 주제입니다. 추상이 어디서 샜는지 말할 수 있다는 것이 "처음부터 완벽했다"
보다 강한 증거라고 봅니다.

> 검증 손잡이: `grep -rn skullking server/src/main/java/com/mirboard/infra/` → **0건**

각 절은 `문제 / 후보 / 선택 / 배제 논거 / 대가` 로 쪼개고, 끝에 `코드·테스트·결정` 앵커를
답니다. 한 절만 읽은 사람도 그 자리에서 검증할 수 있게 하려는 것입니다.

---

## §0 출발점 — 문서가 코드보다 앞서 있었다

D-06/D-11 은 1일차에 *"새 게임 = 패키지 + `GameDefinition` Bean 등록"* 을 약속했습니다.
D-97 에서 실제로 재보니 그건 **카탈로그(`GET /api/games`)까지만 사실**이었습니다.

| 문서의 주장 | 실측 |
| --- | --- |
| `GameEngine`·`GameAction`·`GameEvent` 가 포트다 | 메서드 **0개** 빈 마커 |
| `GameDefinition.newEngine()` 으로 엔진을 얻는다 | 호출부 **0건** — 죽은 코드 |
| 인프라는 게임을 모른다 | infra **10파일**이 티츄를 직접 참조 |

이 절의 값은 기술이 아니라 태도입니다 — **설계 문서가 틀렸다는 걸 자기 손으로 측정해
적었습니다.** `game-port.md` 는 "CLAUDE.md 의 그 문구가 언제부터 참이 되는가"까지
못박아 뒀습니다.

`코드:` `domain/game/core/GameDefinition.java` · `문서:` [game-port.md](game-port.md) §0 ·
`결정:` D-06 · D-11 · D-97

---

## §1 종이에서 표면을 확정한다 (코드 변경 0)

**문제** — 포트를 잘못 잡으면 두 게임을 다 고쳐야 합니다.

**후보** — (a) 지금 바로 일반화 (b) 두 번째 게임을 짜면서 동시에 추출
(c) 종이에서 표면 확정 → 동작 무변경 추출 → 그다음 게임

**선택** — (c). D-97 은 산출물이 문서뿐인 결정입니다(커밋 제목에 "코드 0" 명시).

**배제 논거** — (b) 는 리팩터에 룰 변경이 섞여 이후 회귀의 원인 분리가 불가능해집니다.

표면을 **두 번째가 아니라 세 번째 게임 기준으로** 잡은 것이 이 절의 핵심입니다.
`game-port.md` §1 표에는 "요트가 깨는 지점" 열이 있습니다. 거기서 나온 판단 셋:

1. **팀을 포트에서 뺀다** — 티츄는 2:2 고정이지만 스컬킹·요트는 개인전입니다. 점수를
   `Map<Team,Integer>` 가 아니라 좌석별 `Map<Integer,Integer>` 로 다루고, 팀 합산은 티츄
   내부 관심사로 내렸습니다.
2. **`privateView` 는 Optional** — 요트에는 손패가 없습니다. State Hiding 은 "비공개가
   있으면 반드시 본인 큐로만"이지 "반드시 비공개가 있다"가 아닙니다.
3. **매치 종료 판정을 엔진이 소유** — 티츄 1000점 / 스컬킹 10라운드 고정 / 요트 12칸.
   종료 조건이 전부 달라 인프라가 알면 안 됩니다.

**대가** — 코드가 한 줄도 나오지 않은 커밋 하나를 감수했습니다.

`문서:` [game-port.md](game-port.md) §1 · `결정:` D-97

---

## §2 추출 — 티츄를 포트 뒤로, 룰은 한 줄도 안 고치고

D-98 / 머지 `34c0ec7`. `server/src` 27파일 +1,229/−403, 서버 412건 전량 그린.

**① 2계층 분리의 근거는 아키텍처가 아니라 피드백 루프였습니다.** 순수 `TichuEngine`(539줄,
저장소 없음)과 포트 어댑터 `TichuGameEngine`(323줄)을 합치면 룰 단위 테스트가 Redis 를
끌고 옵니다. 이 분리가 나중에 값을 냅니다(§3 마지막 줄).

**② 인프라의 유일한 진입점** — `GameEngineProvider.forRoom(room)` →
`GameRegistry.require(gameType).newEngine(ctx)`. 죽어 있던 `newEngine()` 이 드디어 호출부를
갖습니다.

**③ 종이 설계에서 구현이 고친 3곳:**

| 설계(D-97) | 구현(D-98) | 이유 |
| --- | --- | --- |
| `isMatchOver(state, MatchProgress)` | `isMatchOver()` 무인자 | 티츄 매치 상태는 팀 점수 + MVP 기여도라 게임 중립 타입으로 무손실 표현이 안 됨 |
| `pendingSeat` 단수 | `pendingSeats` 복수 | 단수면 Dealing/Passing 에서 봇이 사람 좌석의 선언을 기다리며 멈춤 |
| `initialState(seatCount, seed)` | 미채택 | 호출부가 0건이 될 메서드였고, 그게 정확히 D-97 이 `newEngine` 에서 지적한 실패 모드 |

**④ 정직 항목 둘** — Spring Framework 7 의 STOMP 컨버터가 Jackson 3 기반이라 Jackson 2
`JsonNode` 를 `@Payload` 로 받지 못했습니다(`MessageConversionException` 실측). `Map<String,
Object>` 로 우회했고, 부수 효과로 액션 역직렬화와 Redis 직렬화가 같은 매퍼를 쓰게 됐습니다.
그리고 유일한 동작 변경(존재하지 않는 방 rematch 가 409 → 404)을 스스로 기록했습니다.

**결과** — infra → `domain.game.tichu` 의존이 **10파일 → 1파일**. 잔여 1파일의 이유도
적어 뒀습니다: 칩 정산은 "어느 팀이 이겼는가"에 묶여 있어, 방금 포트에서 뺀 팀 개념을
도로 끌어올려야 합니다.

`코드:` `domain/game/core/GameEngine.java`(149줄) · `infra/ws/GameEngineProvider.java` ·
`테스트:` `TichuGameEngine*Test` · `결정:` D-98

---

## §3 두 번째 게임 — 명세부터, 그리고 Docker 없이

S3(D-100) 룰 명세 → S4(D-101) 순수 엔진.

**① 명세가 침묵할 때 무엇을 하는가.** 원문(집단 편집 위키, 2021년판에서 최대 인원 6→8)이
답하지 않는 **20건**을 "확정 + 원문 근거" 표로 남겼습니다([rules-skullking.md](rules-skullking.md) §13).
하나만 인용하면 — 0예측 실패 시 일반칙(차이 × 10)과 특칙(라운드 × 10)이 문면상 **둘 다**
적용되는데 원문에 우선순위가 없습니다. "더 좁은 조건이 우선하고, **합산은 어느 문장으로도
지지되지 않으므로 배제**"로 확정했습니다.

해석이 갈리면 숫자가 갈리는 두 지점은 **판별 테스트**로 못박았습니다 —
`라운드 3, bid=0, won=1 → −30`(일반칙 해석이면 −10),
`3자 트릭 인어 승 = 40점`(포함 기준 해석이면 90점).

출처 신뢰도 경고도 문서 머리에 스스로 달았습니다.

**② 비추이적 순환을 6단 사다리로.** 해적 > 인어 > 스컬킹 > 해적 은 3자 순환이라
`Comparator` 로 표현할 수 없고, "셋 다 나오면 인어" 예외가 얹힙니다.

```java
private record Rung(Predicate<Context> applies, ToIntFunction<Context> pick) {}
```

`LADDER` 6단은 `스컬킹&인어 → 인어` / `스컬킹` / `해적` / `인어` / `색상` / `전부 탈출`.
백미는 **동점 처리를 분기로 두지 않은 것**입니다 — 모든 단의 selector 가 `firstOfKind` 라
"승자 종류가 정해진 뒤 그 종류를 먼저 낸 사람"이 공짜로 성립합니다. 검증은 스컬킹·해적·인어
존재 여부 **8조합 전수 대조**입니다.

**③ 검증 자체를 검증했습니다.** 안 터지는 체커는 아무것도 보장하지 않습니다.
`SkullKingInvariantChecker` 를 통과 케이스뿐 아니라 **고의로 위반시킨 상태 8건**
(+ 오탐 방지 통과 2건)으로 검출 능력 자체를 테스트했습니다.

**여기 한 줄만 숫자를 씁니다** — 서버 테스트 **749건 중 622건(83%)이 Docker 불필요**합니다.
자랑이 아니라 §2 의 2계층 분리가 값을 냈다는 인과 증거입니다.

`코드:` `skullking/trick/TrickResolver.java` · `skullking/invariant/SkullKingInvariantChecker.java` ·
`테스트:` `TrickResolverTest`(8조합 전수) · `DealerTest`(2~8인 × 1~10R 전수 70건) ·
`결정:` D-100 · D-101

---

## §4 계약이 새는 곳 — 정확히 한 군데

**문제** — 스컬킹은 개인전이라 티츄식 탈주 처리(D-75, 상대팀 승리로 즉시 종료)를 옮길 수
없습니다.

**후보** — (a) 좌석 즉시 제거 (b) 라운드 경계에서 좌석 축소 (c) 매치 무효
(d) 유령 좌석 + 자동조종

**선택** — (d). 좌석을 지우지 않고 `desertedSeats` 표식만 남긴 뒤, 엔진이 그 좌석의 차례를
대신 둡니다(미제출 예측 0 고정, 카드는 최약수 전순서, 티그리스는 탈출 선언 —
`timeoutAction` 과 정책 공유).

**배제 논거** (이 절의 본론) — `seatCount` 가 트릭 완성 판정(`played.size() >= seatCount`)·
턴 모듈러 산술·카드 보존 불변식(`handSize × seatCount`)·좌석==인덱스 전제에 **동시에**
묶여 있습니다. 좌석을 빼는 순간 조용한 오판정 표면이 코드 전역에 생깁니다. 유령은 정상
`apply` 경로만 타므로 판정 로직·불변식 체커·기존 테스트가 전부 무변경입니다.

**대가** — 사용 계약이 하나 늘었습니다. 사람 액션 뒤와 라운드 시작 뒤에 유령 차례를
드레인해야 합니다. `applyAndDrain`/`startRoundAndDrain` 이 이를 봉인하고, 2-인자 불변식
체커가 "탈주 좌석이 대기 좌석에 남아 있지 않다"로 누락을 검출합니다.

### 그리고 이 룰 결정이 포트 계약을 바꿨다

`boolean desert(...)` 는 "매치를 강제 종료했는가"라는 2치라서 "남은 사람끼리 계속"을
표현할 수 없었습니다. 3치로 확장했습니다:

```java
enum DesertOutcome { NOT_APPLICABLE, MATCH_CONTINUES, MATCH_ENDED }
```

인프라 쪽 변화는 `DesertionService` 한 곳입니다 — `MATCH_ENDED` 면 기존대로 방을
FINISHED 로, `MATCH_CONTINUES` 면 방을 IN_GAME 으로 유지한 채 봇/타임아웃만 재무장합니다
(락 해제 후 실행 — `BotScheduler` 는 호출자가 락을 쥐지 않은 상태를 요구합니다).

**티츄만 있을 때 이 계약은 완벽해 보였습니다.**

`코드:` `SkullKingEngine.java`(desert/applyAndDrain/startRoundAndDrain) ·
`infra/ws/DesertionService.java` · `테스트:` `SkullKingDesertionTest`(탈주 포함 풀매치 완주) ·
`결정:` D-104 · D-102

---

## §5 붙였을 때 실제로 뭐가 바뀌었나

`git diff --name-status 725a9ad^ 7ab7171 -- server/src/main/java`

**신규 6** — 전부 `domain/game/skullking/*`: `SkullKingGameDefinition`,
`SkullKingGameEngine`, `lifecycle/SkullKingRoundStarter`, `persistence/SkullKingMatchStateStore`,
`persistence/SkullKingStateStore`, `state/SkullKingStateMapper`

**수정 5**

| 파일 | 변경 | 성격 |
| --- | --- | --- |
| `core/GameEngine.java` | +19/−4 | 포트 계약 3치화 |
| `infra/ws/DesertionService.java` | +32/−7 | **인프라 유일** |
| `lobby/auth/BotUserRegistry.java` | +14/−11 | 시드 봇 4→8 (8인 방을 못 채우던 제약은 티츄 4인에선 도달 불가였다) |
| `tichu/TichuGameEngine.java` | +3/−3 | 새 enum 반환 |
| `skullking/card/SkullCard.java` | +3/−5 | skullking 내부 |

**안 바뀐 것을 같은 비중으로 적습니다** — `GameStompController` · `GameEventBroadcaster` ·
`BotScheduler` · `TurnTimeoutScheduler` · `RoomController`(resync) · `MatchProgressService` ·
로비 전체 · Lua 스크립트 9개 · STOMP 목적지.

결정타 두 줄:

- `grep -rniE 'skullking' server/src/main/java/com/mirboard/infra` → **0건**.
  인프라가 두 번째 게임의 이름을 어디서도 모릅니다.
- infra 의 티츄 참조는 **10파일 → 6파일 11행**, 그중 실코드는 **2파일 6행**뿐입니다 —
  `RoomChipService`(문서화된 예외)와 `MirboardMetrics`(`.tag("gameType","TICHU")` —
  **문서화되지 않은 잔재**). 나머지 4파일은 "왜 이렇게 됐는지"를 적은 javadoc 입니다.

규모 대비로 보면, 포트 `domain/game/core` 전체 390줄(그중 `GameEngine.java` 149줄) 뒤에
티츄 4,042줄과 스컬킹 2,919줄이 꽂혀 있습니다.

`검증:` 아래 §부록 명령 · `결정:` D-102

---

## §6 클라이언트에도 같은 수술

D-103 / 머지 `95b4bc6`. 29파일 **+3,583/−62** — 기존 코드 수정이 62줄뿐입니다.

**문제** — `useStompRoom` 이 `useTichuStore` 를 하드코딩(import + 셀렉터 6개 + 비공개 큐
3분기)하고 있어 두 번째 게임의 이벤트를 받을 자리가 없었습니다.

**선택** — 훅의 이름·위치·반환을 그대로 두고 **입력만** `RoomEventSink` 주입으로 확장.
훅은 게임 스토어를 import 하지 않고, 게임별 sink 파일이 스토어에 꽂습니다.

**배제 논거** — 대안은 `useRoomSocket` 을 새로 추출하고 `useStompRoom` 을 티츄 전용
어댑터로 남기는 것이었습니다. 두 안의 메커니즘은 같지만 **옮기는 코드량이 다릅니다**.
그리고 기존 `GameTable.test` 가 `useStompRoom` 을 인자 무시 팩토리로 모킹하고 있어서
**이 수술은 기존 144건이 검증해주지 못합니다** — 즉 옮긴 줄 수가 곧 미검증 위험량이고,
적은 쪽이 옳습니다. 부족한 안전망은 신규 테스트 2종으로 메웠습니다.

**대가** — 훅이 sink 를 ref 로 잡아 effect 의존성에서 빼기 때문에 stale closure 위험이
생깁니다. "sink 는 모듈 상수 + 각 메서드는 호출 시점에 `getState()`" 를 규약으로 못박고
주석에 이유를 적었습니다.

`grep -rn 'tichu|skullking' client/src/ws/useStompRoom.ts` → **0건**.

`코드:` `client/src/ws/roomEventSink.ts` · `client/src/ws/useStompRoom.ts` ·
`테스트:` `useStompRoom.sink.test.tsx` · `tichuRoomSink.test.ts` · `결정:` D-103

---

## §7 남은 것 — 스스로 공개하는 부채

포트가 **완성됐다고 주장하지 않습니다.** 두 번째 게임까지만 검증됐고, 세 번째 게임(요트)은
`game-port.md` §4 가 "종이 통과도 남은 과제"로 적어 뒀습니다.

- **`MirboardMetrics:33` 의 `.tag("gameType","TICHU")` 하드코딩** — §5 에서 실코드 잔여를
  "2파일"이라고 쓴 이유입니다. 칩 정산과 달리 이건 문서화된 예외가 아니라 잔재입니다.
- **스컬킹 매치 결과·ELO 미영속** — `users.rating` 을 게임별로 나눌지가 선행 결정(D-02
  스키마 원칙과 충돌)이라 의도적으로 보류했습니다.
- **끊김 유예(120s) 구간의 정지** — 탈주 확정 전이라 유령 자동조종이 켜지지 않습니다.
- **seq 판정 중복** — `tichuStore` 분리가 이월(D-95)이라 판정 로직 ~40줄이 두 스토어에
  중복돼 있습니다. 세 번째 게임 전에 "판정은 훅으로, sink 는 순수 리듀서로" 승격이 필요합니다.

---

## 부록 — 재현 명령

```bash
# (1) 인프라가 두 번째 게임의 이름을 아는가
grep -rniE 'skullking' server/src/main/java/com/mirboard/infra   # → 0건

# (2) 인프라의 티츄 잔여 참조 → 6파일 11행, 그중 실코드 2파일 6행
#     (-i 필수: MirboardMetrics 는 대문자 문자열 "TICHU" 라 대소문자 구분 grep 이 놓친다)
grep -rniE 'tichu' server/src/main/java/com/mirboard/infra --include='*.java'

# (3) 스컬킹 통합에서 실제로 바뀐 파일
git diff --name-status 725a9ad^ 7ab7171 -- server/src/main/java

# (4) 클라 통합 규모
git diff --stat d1c5ca1^ 95b4bc6 -- client/src | tail -1

# (5) 룰 테스트는 Docker 없이 돈다
./scripts/check.sh rules
```

관련 문서: [game-port.md](game-port.md)(포트 계약 정본) ·
[rules-skullking.md](rules-skullking.md)(룰 ↔ 코드 1:1) ·
[decisions.md](decisions.md)(D-97 ~ D-104)
