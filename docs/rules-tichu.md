# 티츄 룰 명세 (Tichu Rules — Code Mapping)

본 문서는 Mirboard 의 티츄 룰을 **코드와 1:1 매핑** 한다. 단일 진실 공급원
(single source of truth) — 코드와 본 문서가 어긋나면 코드를 수정하거나
본 문서의 룰 결정 항목 (`docs/decisions.md` D-NN) 을 갱신한다.

표기:
- **코드:** 핵심 구현 위치 (`path:line`)
- **테스트:** 검증하는 테스트 파일
- **갭:** 현재 미검증/미구현 영역 (있을 경우)

---

## 1. 카드 + 덱 (56장)

- 4 슈트 × 13 rank (2..14) = **52 일반 카드**
- 4 **특수 카드**: Mahjong, Dog, Phoenix, Dragon
- 일반 rank 의미: 2..10 숫자 그대로, 11=J, 12=Q, 13=K, 14=A
- 특수 카드 내부 rank (정렬/비교용):
  - **Dog** = 0 (실 룰에서는 단독 플레이 전용)
  - **Phoenix** = 0 (컨텍스트별 별도 처리)
  - **Mahjong** = 1 (스트레이트 최저 시작점)
  - **Dragon** = 100 (항상 최강 단일)

**카드 점수**: 5 → 5점, 10 → 10점, K(13) → 10점, Dragon → +25, Phoenix → −25, 그 외 0.
- **코드:** `Card.points()` (`server/src/main/java/com/mirboard/domain/game/tichu/card/Card.java:73-82`)
- **덱 빌드:** `Deck.buildAllCards` (`Deck.java:53-65`) — 52 normal + 4 special, 결정적 순서
- **테스트:** `CardTest` (모든 카드 점수 검증), `DeckTest` (56장, 슈트별 13장)
- **갭:** Phoenix 가 4등 손에 남은 채 라운드 종료 시 -25 점이 상대팀으로 가는 명시적 단위 테스트 (`ScoreCalculatorTest` 의 phoenix_in_trick_pile 은 트릭 안 Phoenix 만 검증).

---

## 2. 라운드 라이프사이클

`Dealing(8)` → `Dealing(14)` → `Passing` → `Playing` → `RoundEnd`

| 단계 | 진입 조건 | 액션 | 다음 단계 트리거 |
| --- | --- | --- | --- |
| Dealing(8) | 라운드 시작 | DeclareGrandTichu / Ready | 4명 ready |
| Dealing(14) | 8 단계 4명 ready | DeclareTichu / Ready | 4명 ready |
| Passing | 14 단계 4명 ready | PassCards (좌/파트너/우 각 1장) | 4명 모두 submit |
| Playing | 4명 swap 완료 | PlayCard / PassTrick / DeclareTichu (첫 플레이 전) / MakeWish / GiveDragonTrick | 3명 완주 또는 더블 빅토리 |
| RoundEnd | shouldEndRound | (없음) | matchProgress 가 다음 라운드 또는 매치 종료 |

- **코드:** `TichuState` sealed (`state/TichuState.java`), `TichuEngine.applyReady/applyPassCards/applyPlayCard`, `TichuRoundStarter.startRound` (`lifecycle/TichuRoundStarter.java`)
- **테스트:** `DealingLifecycleTest`, `TichuEngineRoundSimulationTest`, `BotMatchSimulationIT`
- **갭:** 없음 — 라이프사이클은 견고.

---

## 3. 선언 (Grand Tichu / Tichu)

- **Grand Tichu**: Dealing(8) 윈도우, 8장만 본 상태에서 선언. 성공 ±200, 실패 −200.
- **Tichu**: Dealing(14) 윈도우 또는 Playing 첫 플레이 전 (handSize=14). 성공 ±100, 실패 −100.
- 한 라운드 한 사람당 1개 선언만 (중복 금지). Grand 선언자는 Tichu 추가 불가.
- 성공 조건: 선언자 == 1등 완주자.

**상수:**
- `TICHU_BONUS = 100`, `GRAND_TICHU_BONUS = 200` (`scoring/CardPoints.java:20-21`)

**코드:**
- 액션: `TichuAction.DeclareGrandTichu/DeclareTichu` (`action/TichuAction.java:35-48`)
- 검증: `ActionValidator.validateDeclareGrandTichu/validateDeclareTichu` (`action/ActionValidator.java:102-146`)
- 점수: `ScoreCalculator.declarationBonus` (`scoring/ScoreCalculator.java:105-119`)
- enum: `TichuDeclaration { NONE, TICHU, GRAND_TICHU }`

**테스트:** `ActionValidatorTest`, `ScoreCalculatorTest.{successful,failed}_{tichu,grand_tichu}_*`

**갭:** 없음.

---

## 4. 카드 패스

14장 단계 종료 후 각 좌석이 좌(`(seat+1)%4`)/파트너(`(seat+2)%4`)/우(`(seat+3)%4`) 에게
각 1장씩 패스 (총 3장). 4명 모두 제출하면 동시 스왑.

- **3장 distinct** 강제 (같은 카드 중복 X)
- 본인 손에 있는 카드만

**코드:**
- 액션: `TichuAction.PassCards(toLeft, toPartner, toRight)` (`TichuAction.java:50-52`)
- 검증: `ActionValidator.validatePassCards` (`ActionValidator.java:159-175`)
- 스왑: `TichuEngine.swapAndStartPlaying` (`TichuEngine.java:165-193`) — 동시에 보낸 3장 제거 + 받은 3장 추가, Mahjong holder 가 리드

**테스트:** `ActionValidatorTest`, `TichuEngineRoundSimulationTest` (스왑 후 Playing 진입)

**갭:** 패스 후 받은 카드 위치 (hand 끝에 append) 가 클라 UI 의 정렬 정책과 일치하는지 명시되지 않음. 현재는 임의 — 클라가 정렬.

---

## 5. 핸드 8 타입 + 분류 우선순위

| 타입 | 조건 | 비고 |
| --- | --- | --- |
| SINGLE | 1장 | 일반 카드 또는 Mahjong/Dog/Phoenix/Dragon |
| PAIR | 동일 rank 2장 | 특수카드 제외 (Phoenix 와일드 가능) |
| TRIPLE | 동일 rank 3장 | 특수카드 제외 (Phoenix 와일드 가능) |
| FULL_HOUSE | 3+2 (5장) | Phoenix 와일드 가능 |
| STRAIGHT | ≥5 장 연속 rank | Mahjong (1) 시작 가능, Phoenix 와일드 가능, Dragon/Dog 불가 |
| CONSECUTIVE_PAIRS | ≥6 장 짝수, 모두 2장씩 동일 rank 연속 | Phoenix 와일드 가능 |
| BOMB | 동일 rank 4장 | 특수카드 불가, **모든 비-BOMB 핸드 깸** |
| STRAIGHT_FLUSH_BOMB | ≥5 장 동일 suit + 연속 rank | 특수카드 불가, **BOMB 보다 강함** |

**우선순위:** detect 는 STRAIGHT_FLUSH_BOMB → BOMB → 일반 순. 같은 카드 셋이 여러 타입으로 해석 가능하면 가장 강한 타입.

**코드:**
- enum: `HandType` (`hand/HandType.java`)
- 분류: `HandDetector.detect` (`hand/HandDetector.java:20-208`)

**테스트:** `HandDetectorTest` (30+ 시나리오), `PhoenixDetectionTest` (11)

**갭:** 5-pair 이상의 CONSECUTIVE_PAIRS (10장), 7장 STRAIGHT_FLUSH_BOMB 같은 큰 사이즈는 명시 케이스 부재. 코드는 일반화되어 동작할 것으로 예상.

---

## 6. Phoenix 와일드

- **단독 SINGLE**: 특별 처리 — `phoenixSingle=true` 플래그.
- **콤보 (PAIR/TRIPLE/FULL_HOUSE/STRAIGHT/CONSECUTIVE_PAIRS)**: rank 2..14 순회 대체 시도, 가장 강한 비-BOMB 해석 선택.
- **BOMB / STRAIGHT_FLUSH_BOMB 불가** — Phoenix 는 폭탄 구성원 될 수 없음.
- **Dragon/Dog 와 콤보 불가** — 특수카드 mix 금지.

**effectiveRank** (단독 SINGLE):
- 리드 시: 1 (Mahjong 위)
- follow 시: currentTop.rank (같은 rank 지만 +0.5 우위로 이김)
- Dragon (rank 100) 은 못 이김

**코드:**
- 와일드 치환: `HandDetector.detectWithPhoenix` (`HandDetector.java:65-94`)
- 단독 정규화: `TichuEngine.normalizePhoenix` (`TichuEngine.java:494-498`)
- 비교: `HandComparator.canBeat` 의 Phoenix 단독 분기 (`HandComparator.java:27-32`)

**테스트:** `PhoenixDetectionTest` (콤보 와일드), `PhoenixComparatorTest` (8 — Phoenix vs Mahjong/Ace/Dragon/Phoenix-pair)

**갭:** 2-pair + Phoenix → FULL_HOUSE (4 rank) 해석 명시 케이스 부재.

---

## 7. 핸드 비교 (canBeat)

규칙 (우선순위 위에서 아래):
1. **STRAIGHT_FLUSH_BOMB** 가 도전자 → 모든 것 깸. SFB 끼리는 길이 → rank.
2. **BOMB** 가 도전자, currentTop 이 비-BOMB → 깸. BOMB 끼리는 rank.
3. **Phoenix 단독 SINGLE** 도전: currentTop 이 SINGLE 일 때만 (Dragon 제외).
4. **일반 케이스**: 동타입 + 동길이 + 도전자 rank > currentTop rank.

**코드:** `HandComparator.canBeat` (`hand/HandComparator.java:23-63`)

**테스트:** `HandComparatorTest` (11 — Dragon 최강, BOMB-비-BOMB 끊음, SFB > BOMB, 길이 다름 제외, null 방어), `PhoenixComparatorTest` (8)

**갭:** 같은 길이 다른 rank PAIR/TRIPLE 비교 (예: 3-pair vs 9-pair) 명시 케이스 부재 — 코드는 작동.

---

## 8. 특수 카드 4종 (세부 룰)

### 8.1 Mahjong (rank 1)
- **첫 리드 강제**: 라운드 시작 (Playing 진입) 시 Mahjong 보유자가 첫 리드.
- **Wish 활성**: Mahjong 을 단독 또는 콤보로 낸 **직후** 한 번에 한해 rank 2..14 중 하나를 wish 로 지정 가능 (생략 가능).
- Mahjong 은 일반 SINGLE 처럼 동작 (rank 1, 가장 약함). 콤보 내 일반 카드와 섞일 수 없음 (STRAIGHT 의 시작 1만 허용).

**코드:**
- 리드 결정: `TichuEngine.mahjongHolder` (`TichuEngine.java:195-202`)
- Wish 액션: `TichuAction.MakeWish(rank)`, `applyMakeWish` (`TichuEngine.java:328-338`)
- Wish 검증: `ActionValidator.validateMakeWish` (`ActionValidator.java:178-196`) — Mahjong 직후 + currentTopSeat==me + 중복 금지

**테스트:** `ActionValidatorTest` (Wish 활성/비활성 케이스).

**갭:** Mahjong 자체를 콤보 (예: 1-2-3-4-5 STRAIGHT) 일부로 낸 후 wish 가능한지 명시 부재. 현재 ActionValidator 는 `currentTop.cards == [Mahjong]` 단독 요구.

### 8.2 Dog (rank 0)
- **solo + lead 만 허용** — follow 또는 콤보 안 됨.
- 즉시 트릭 폐쇄 + 파트너 (`(seat+2)%4`) 가 새 리드.
- **파트너 완주 시** → `nextActiveSeat(players, partner)` 로 다음 active 좌석에 리드 (D-52 fix).
- 점수 0 (트릭 점수는 빈 트릭, 카드 자체도 0).
- **Dog 카드 자체는 nextLead 의 `tricksWon` 으로 보존** (점수 0 이라 score 영향 X) — D-59. 카드 보존 invariant 만족용.

**코드:**
- 검증: `ActionValidator.validatePlayCard` Dog 분기 (`ActionValidator.java:63-66`)
- 처리: `TichuEngine.applyPlayCard` Dog 분기 (`TichuEngine.java:232-245`)

**테스트:** `ActionValidatorTest` (solo lead allow, follow reject), `BotMatchSimulationIT` 가 nextActiveSeat fallback 회귀 catch (D-52).

**갭:** Dog 가 wish 활성 상태에서 lead 로 나올 때 wish rank 만족 어긋남 — Dog 는 solo 라 wish rank 못 포함. ActionValidator 가 wish 강제하지 않는지 명시 부재 (10C 와 연계).

### 8.3 Phoenix (-25 점, rank 0)
- **단독 SINGLE wild**: §6 참고. lead=1, follow=top.rank+0.5.
- **콤보 wild**: PAIR/TRIPLE/FULL_HOUSE/STRAIGHT/CONSECUTIVE_PAIRS 안에서 임의 rank 대체.
- **BOMB / SFB 불가**.
- **Dragon 못 이김**.

§6 참고.

### 8.4 Dragon (+25 점, rank 100)
- **항상 최강 단일** (Phoenix follow 포함, 모든 SINGLE 위).
- 트릭을 가져가면 **GiveDragonTrick 강제** (pending) — 상대팀 좌석 중 하나에게 양도.
- 양도 시 accumulated cards (트릭 점수) 가 recipient 의 tricksWon 으로 이전.
- 양도 후 다음 리드는 dragon player (또는 dragon player 가 완주했으면 nextActiveSeat).

**코드:**
- 처리: `TichuEngine.applyGiveDragonTrick` (`TichuEngine.java:342-368`)
- 트릭 폐쇄 분기: `TichuEngine.closeTrickAndContinue` 의 dragonWon (`TichuEngine.java:381-394`)
- 검증: `ActionValidator.validateGiveDragonTrick` (`ActionValidator.java:199-216`)

**테스트:** `ActionValidatorTest` (recipient team check, 비-Dragon reject).

**갭:** **Dragon 양도 후 점수 이전 (recipient.tricksWon += accumulated) 의 명시적 단위 테스트 부재.** 10B 에서 추가 예정.

---

## 9. Wish 강제

Mahjong 으로 활성된 wish 가 있는 동안 모든 플레이어는 가능한 한 wish rank 카드를 포함한 합법 플레이를 해야 한다.

**현재 구현 (`ActionValidator.java:76-86`)**:
- **lead**: 보유한 wish rank + 미포함 플레이 → reject (`WISH_NOT_FULFILLED`)
- **follow**: **deferred** — line 83 주석 "Strict only on lead; on follow, deferred (need beat check)". 현재 미구현. **10C 에서 마감 예정**.

**fulfill** 시점: 플레이 카드에 wish rank 가 한 번이라도 포함되면 `Wish.fulfill()` → activeWish.fulfilled=true. 이후 trick 진행 동안 다음 트릭으로 전파 (`TichuEngine.closeTrickAndContinue` 의 `trick.activeWish()` 유지). 그러나 fulfilled wish 는 더 이상 강제되지 않음 (`Wish.isActive()` → false).

**코드:**
- 검증: `ActionValidator.validatePlayCard` wish 분기 (`ActionValidator.java:76-86`)
- fulfill: `TichuEngine.applyPlayCard` (`TichuEngine.java:247-253`)
- enum: `Wish(int rank, boolean fulfilled)` (`card/Wish.java`)

**테스트:** `ActionValidatorTest` (lead + 보유 + 미포함 → reject).

**갭:** follow 강제 미구현. wish + BOMB 인터럽트 시 fulfillment 처리 명시 부재. **10C 에서 마감**.

---

## 10. BOMB 인터럽트 (out-of-turn)

차례가 아닌 플레이어도 BOMB (또는 SFB) 으로 currentTop 을 깰 수 있다.

- 자기 차례 검사: `currentTurnSeat != seat` 인데 BOMB/SFB 이면 통과 (`ActionValidator.java:57-60`).
- BOMB 끼리도 인터럽트 가능 — 더 강한 BOMB (SFB > BOMB, 같은 타입 + 큰 rank).
- 인터럽트 후 currentTurnSeat 은 누가 되는지: 엔진은 advanceTurn 으로 다음 좌석 결정 (BOMB 친 사람의 다음).

**코드:**
- 검증: `ActionValidator.validatePlayCard:57-60`
- 비교: `HandComparator.canBeat` (`HandComparator.java:37-58`)
- 턴 처리: `TichuEngine.applyPlayCard` (일반 PlayCard 와 동일 흐름, advanceTurn 호출)

**테스트:** `ActionValidatorTest` (Bomb out-of-turn allow).

**갭:** wish 활성 + BOMB 인터럽트 + 보유 wish rank 시 fulfillment 명시 부재. 10B/10C 에서 확정.

---

## 11. 트릭 종료

- 모두 패스 (또는 완주) → `currentTurnSeat == currentTopSeat` 도달 → 트릭 폐쇄.
- **일반**: `currentTopSeat` 플레이어가 accumulated cards 를 tricksWon 으로 가져감. 새 리드는 taker (완주 시 nextActiveSeat).
- **Dragon**: dragonWon → pending 상태, GiveDragonTrick 액션 대기. 양도 후 양도자 (또는 그 next active) 가 새 리드.
- **Dog**: 즉시 폐쇄 + 점수 0 (Dog 만 accumulated 에 들어감, 카드 점수 0).

**코드:** `TichuEngine.closeTrickAndContinue` (`TichuEngine.java:373-419`), `applyPlayCard` Dog 분기 (`TichuEngine.java:232-245`)

**테스트:** `TichuEngineRoundSimulationTest.pass_closes_trick_when_all_others_pass`, Dog 케이스 부재 — **10B 에서 추가**.

---

## 12. 라운드 종료

- **3명 완주 시** 즉시 종료.
- **더블 빅토리**: 같은 팀 두 명이 1, 2등 연속 완주 시 즉시 종료 (3등 완주 전).

라운드 종료 시 진행 중 트릭이 있으면 `currentTopSeat` 가 가져감 (`endRoundClosingTrick`).

**코드:**
- 종료 검사: `TichuEngine.shouldEndRound` (`TichuEngine.java:453-464`)
- 진행 중 트릭 처리: `TichuEngine.endRoundClosingTrick` (`TichuEngine.java:421-436`)

**테스트:** `TichuEngineRoundSimulationTest.three_finish_round_ends_with_score`, `double_victory_ends_round_immediately_after_partners_finish`

**갭:** 없음.

---

## 13. 라운드 점수

`ScoreCalculator.compute(players)` (`scoring/ScoreCalculator.java:25-81`).

**더블 빅토리 분기:**
- 승팀 = 1, 2등 완주자 팀
- 승팀 += 200 (`DOUBLE_VICTORY_BONUS`)
- 트릭/손 점수 합산 생략
- 선언 보너스 ± 별도 합산

**정상 종료 분기 (3명 완주):**
- 각 비-loser 의 tricksWon 점수 → 각자 팀
- loser 의 tricksWon → 1등 완주자 팀
- loser 의 손 잔여 카드 점수 → 상대팀
- 선언 보너스 ± 합산 (성공 = 선언자 == 1등 완주자)

**불변:** 비-더블빅토리 라운드의 모든 카드 점수 합 = 100 (`ScoreCalculatorTest.all_card_points_sum_is_one_hundred`).

**테스트:** `ScoreCalculatorTest` 13 케이스.

**갭:** 없음 (단위는 견고). 통합 시뮬레이션 (10D invariant) 에서 라운드 끝마다 100 합 재확인 예정.

---

## 14. 매치 종료

- **종료 조건**: `cumulativeA >= 1000 || cumulativeB >= 1000` **AND** `cumulativeA != cumulativeB`
- **동점 1000**: 매치 계속
- **승팀**: 누적 점수 높은 팀

**코드:** `TichuMatchState.isMatchOver` / `winningTeam` (`persistence/TichuMatchState.java:55-66`)

**테스트:** `TichuMatchStateTest` (6) — 일반 종료 / 동점 1000 / 한쪽만 1000 / winningTeam 예외.

**갭:** 없음.

---

## 본 문서 ↔ 코드 동기화 규칙

- 룰 변경 시 본 문서 + `docs/decisions.md` D-NN + 코드 + 테스트를 같은 commit 으로 묶는다.
- 본 문서의 "갭" 항목은 Phase 10B/10C/10D 진행 시 해소되거나 명시적으로 "보류 — 향후 작업" 으로 기록.
- ActionValidator / TichuEngine / ScoreCalculator 변경 시 본 문서의 해당 섹션 line 번호를 갱신 (코드 line shift 가능).

마지막 갱신: Phase 10A (D-56 참고).
