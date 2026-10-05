# 티츄 룰 명세 (Tichu Rules — Code Mapping)

본 문서는 Mirboard 의 티츄 룰을 **코드와 1:1 매핑** 한다. 단일 진실 공급원
(single source of truth) — 코드와 본 문서가 어긋나면 코드를 수정하거나
본 문서의 룰 결정 항목 (`docs/decisions.md` D-NN) 을 갱신한다.

표기:
- **코드:** 핵심 구현 위치 (`path:line`)
- **테스트:** 검증하는 테스트 파일
- **갭:** 현재 미검증/미구현 영역 (있을 경우)

> **클라 판별기 주의 (D-63)**: `client/src/features/tichu/handType.ts` 는 선택
> 카드 조합명을 **표시용 hint** 로만 판별한다. 서버 `HandDetector` /
> `ActionValidator` 가 룰의 단일 진실공급원이며 합법성 강제는 서버가 한다.
> 클라 판별기는 Phoenix 와일드 등 복잡 케이스를 best-effort 로 처리하고 애매하면
> "?" 를 표시한다. 클라와 서버 판정이 달라도 실제 플레이는 서버 판정을 따른다.

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
| Playing | 4명 swap 완료 | PlayCard(마작 시 wishRank 동봉) / PassTrick / DeclareTichu (첫 플레이 전) / GiveDragonTrick | 3명 완주 또는 더블 빅토리 |
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
| CONSECUTIVE_PAIRS | ≥4 장(2 pair 이상) 짝수, 모두 2장씩 동일 rank 연속 (D-73) | Phoenix 와일드 가능 |
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
- **Wish 활성**: Mahjong 을 내는 **그 액션에 함께** rank 2..14 중 하나를 wish 로 지정 (생략 가능). 단독 리드든 콤보(예: 1-2-3-4-5 STRAIGHT)의 일부든 무관 — 낸 카드에 Mahjong 이 포함되면 지정 가능. **별도 `MAKE_WISH` 액션은 없다** (D-109 폐기 — 사후 별도 창은 다음 플레이어가 카드를 내는 순간 닫혀 실사용이 불가능했다).
- Mahjong 은 일반 SINGLE 처럼 동작 (rank 1, 가장 약함). 콤보 내 일반 카드와 섞일 수 없음 (STRAIGHT 의 시작 1만 허용).

**코드:**
- 리드 결정: `TichuEngine.mahjongHolder`
- Wish 지정: `TichuAction.PlayCard(cards, wishRank)` 의 `wishRank` — `TichuEngine.applyPlayCard` 가 `Wish.active(...)` 세팅 + `WishMade` 이벤트 발행
- Wish 검증: `ActionValidator.validatePlayCard` 의 소원 지정 절 — `wishRank != null` 인데 낸 카드에 Mahjong 이 없으면 `WISH_OUT_OF_CONTEXT`, rank 가 2..14 밖이면 `INVALID_WISH_RANK`

**테스트:** `ActionValidatorTest` (마작 단독 / 스트레이트 동봉 / 마작 미포함 / 랭크 범위), `TichuSpecialCardScenarioTest` (동봉 1회로 활성 + 다음 좌석이 그 위에 낸 뒤에도 유지).

**갭:** 없음 — D-109 에서 콤보 동봉 갭이 닫혔다. "소원 1회" 제한은 Mahjong 이 덱에 1장뿐이라 구조적으로 보장되므로 별도 중복 검사가 없다.

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

**갭 (D-118 에서 확인, 문서화만 — 수정은 별도 D-NN):**
- **(a) 즉시 양도 대기**: 용이 top 이 되는 순간 대기 좌석이 용 주인 하나로 바뀌고
  (`TichuPendingSeats`), `ActionValidator.validateGiveDragonTrick` 은 다른 좌석이 모두
  패스했는지 보지 않는다. 봇·타임아웃은 곧바로 양도해 트릭이 닫히므로 사실상 아무도 용에
  응수(폭탄 포함)할 수 없다. 표준 룰은 모두 패스해 트릭을 이긴 **뒤에** 양도하고, 그 전에는
  폭탄으로 끊을 수 있다.
- **(d) 용으로 라운드 종료 시 무양도**: 용을 낸 그 플레이로 라운드가 끝나면(3명 완주·더블
  빅토리) `TichuEngine.endRoundClosingTrick` 이 양도 없이 용 주인에게 트릭을 넘긴다.

---

## 9. Wish 강제

Mahjong 으로 활성된 wish 가 있는 동안 모든 플레이어는 가능한 한 wish rank 카드를 포함한 합법 플레이를 해야 한다.

**현재 구현 (`ActionValidator.validatePlayCard` 의 wish 강제 절)**:
- **lead**: 보유한 wish rank + 미포함 플레이 → reject (`WISH_NOT_FULFILLED`)
- **follow**: wish rank 를 포함한 **합법 follow 가 존재하면** 미포함 플레이 → reject. 존재 판정은 `WishFulfillmentChecker.canPlayWishRank`. **10C(D-58) 에서 마감 완료**.

**fulfill** 시점: 플레이 카드에 wish rank 가 한 번이라도 포함되면 `Wish.fulfill()` → activeWish.fulfilled=true. 이후 trick 진행 동안 다음 트릭으로 전파 (`TichuEngine.closeTrickAndContinue` 의 `trick.activeWish()` 유지). 그러나 fulfilled wish 는 더 이상 강제되지 않음 (`Wish.isActive()` → false).

**해제 이벤트 (D-126)**: 활성 소원이 사라지는 두 경로 — 위 fulfill, 아래 갭 (b) 의 용 양도 — 에서 공개 `WishCleared(rank)`(`WISH_CLEARED`)를 낸다. 룰 동작은 그대로이고 알림만 더했다. 전에는 이벤트가 없어 클라의 소원 표시가 매 플레이 resync(순번 구멍 때문에 우연히 일어나던)에 기대 지워졌다.

**코드:**
- 검증: `ActionValidator.validatePlayCard` wish 분기 (`ActionValidator.java:76-86`)
- fulfill: `TichuEngine.applyPlayCard` (`TichuEngine.java:247-253`)
- enum: `Wish(int rank, boolean fulfilled)` (`card/Wish.java`)

**테스트:** `ActionValidatorTest` (lead + 보유 + 미포함 → reject), `TichuEngineWishClearedTest` (해제 이벤트, D-126).

**갭:** `WishFulfillmentChecker` 가 콤보(스트레이트/풀하우스/연속페어)를 보지 않아, 콤보로만 wish rank 를 낼 수 있는 상황에서는 강제되지 않는다. wish + BOMB 인터럽트 시 fulfillment 처리도 명시 부재.

**갭 (D-118 에서 확인, 문서화만):**
- **(b) 용 양도 시 소원 소멸**: `TichuEngine.applyGiveDragonTrick` 이 다음 트릭을
  `TrickState.lead(nextLead, null)` 로 시작해 **활성 소원이 사라진다**. 일반 트릭 종료와 개는
  소원을 이어 간다.
- **PassTrick 은 소원 강제를 받지 않는다**: `validatePassTrick` 에 소원 검사가 없어, 소원
  랭크를 쥐고 낼 수 있어도 패스가 통과한다. 봇(§16, F2)은 가드가 아닐 때 자발적으로 지키지만 사람·랜덤 봇은
  빠져나갈 수 있다.

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

**갭 (D-118 에서 확인, 문서화만):** **(c) 패스 영구** — 한 번 패스한 좌석은 그 트릭이 끝날
때까지 `passedSeats` 에 남아 `TurnManager.advanceTurn` 이 건너뛴다. 차례가 다시 돌아와도 낼 수
없다. 표준 룰은 패스한 뒤에도 다음 차례에 다시 낼 수 있다.

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

- **목표점수 (Phase 12, D-65)**: 방 생성 시 프리셋 300/500/700/1000 중 선택
  (기본 1000). `Room.targetScore` → `GameStartingEvent` → `TichuMatchState.targetScore`.
- **종료 조건**: `cumulativeA >= target || cumulativeB >= target` **AND** `cumulativeA != cumulativeB`
  (target = `TichuMatchState.effectiveTarget()` — 구 JSON/0 은 1000 폴백)
- **동점 (target 도달 동점)**: 매치 계속
- **승팀**: 누적 점수 높은 팀

**코드:** `TichuMatchState.isMatchOver` / `effectiveTarget` / `winningTeam`
(`persistence/TichuMatchState.java`)

**테스트:** `TichuMatchStateTest` — 기본 1000 (6) + 커스텀 target 300/500/0폴백 (4).
`RoomControllerIntegrationTest` — targetScore 지정/기본 2 케이스.

**갭:** 없음.

---

## 15. 개인 턴 타임아웃 자동 진행 (Phase 13D, D-66)

방 생성 시 `turnSeconds` (프리셋 끔=0 / 30 / 60 / 90, 기본 끔) 지정. 0 이면
타이머 없음 (기존 동작 완전 호환). >0 이면 한 좌석이 그 시간 내 행동하지 않으면
서버가 **결정적 안전 액션**을 대신 적용해 다음 순서로 넘긴다 — 게임이 멈추지 않음.

자동 액션 우선순위 (`TimeoutActionPolicy`, `LegalActionEnumerator` 합법 후보 중):
1. **GiveDragonTrick** — Dragon 트릭 양도 보류 해소 (상대팀 첫 좌석)
2. **Ready** — Dealing 단계
3. **PassCards** — Passing 단계 (enumerator 3장 조합)
4. **PassTrick** — Playing, 리드가 아니면 (손패 보존)
5. **PlayCard** — 리드라 패스 불가 시 가장 약한 단일 카드

**코드:** `infra/bot/TurnTimeoutScheduler.java` (ScheduledExecutorService +
per-room generation gen-guard + RoomActionLock 공유, BotScheduler 와 동일
동시성 패턴), `domain/game/tichu/bot/TimeoutActionPolicy.java`.

**테스트:** `TurnTimeoutSchedulerIT` — 비-봇 host idle 좌석을 타임아웃이 매 턴
자동 진행해 매치 완주 + invariant. `RoomControllerIntegrationTest` — turnSeconds
지정/기본 2 케이스.

봇 좌석의 수는 이 정책이 아니라 §16 `HeuristicBotPolicy` 가 정한다 — 타임아웃 정책은 사람
무응답용 안전 액션으로 봇 정책과 별개다.

**갭/제약:** 단일 머신 배포 전제 (타이머 in-memory). 다중 인스턴스 전환 시 Redis
동기화 필요 (Phase 6D 패턴, 범위 외). 클라 실시간 카운트다운 UI 는 후속 (현재는
"턴 제한 N초" 정적 배지만).

---

## 16. 봇 정책 (D-118)

솔로 방 봇 좌석의 수는 `TichuGameEngine.botAction` → `HeuristicBotPolicy.choose` 가 정한다.
룰이 아니라 **정책**이지만, 봇이 엔진의 현재 동작(아래 (a)~(d))을 전제로 튜닝되므로 룰 문서에
같이 둔다.

**원칙**
- **공개 정보만** 본다. 상태는 `BotView.of(state, seat)` 로만 읽는다 — 본인 손패, 좌석별
  장수·선언·완주, 공개 트릭(top·차례·패스 좌석·활성 소원·트릭 점수), 나온 카드, 내 용 양도
  보류. 남의 손패, `reservedSecondHalf`(내 몫 포함), 남의 패스 선택은 보지 않는다.
- **결정적**: Random·시간·해시 순회 없음. 포트의 `random` 인자는 무시한다. 같은 상태면 같은 수.
- 후보는 `LegalActionEnumerator.enumerateFull`(기존 후보 + `ComboFinder` 조합 + 선언을
  `ActionValidator` 로 거른 목록) 안에서만 고른다. 각 규칙은 "legal ∩ 규칙 술어"에서 고르고
  비면 다음 규칙으로 넘어간다. 제안은 다시 검증하고, 실패하면 `TimeoutActionPolicy` 로 폴백.
- **평가 함수** `HandPlanner`: 손패를 묶음으로 분해(폭탄 예약 → 4변형 그리디 → (묶음 수,
  루저 수) 최소). **컨트롤** = 용, 봉황 단독, A 단독, 폭탄, K 이상 페어·연속페어, Q 이상
  트리플·풀하우스, A 로 끝나는 스트레이트. **루저** = 비컨트롤 단일·페어 중 rank ≤10, 트리플
  중 ≤8. cost = 묶음 수 + 루저 수. 봉황은 둘 이상의 묶음을 합칠 때만 조합에 쓴다.

**공통 술어** (엔진을 고치면 여기만 다시 보정)
- **가드(partnerHoldBack)**: 파트너 선언 ∧ 파트너 미완주 ∧ 완주자 없음 ∧ 내 선언 없음 ∧ 상대
  위협 없음(활성 상대 중 장수 ≤2 이면서 파트너 장수 이하인 좌석 없음). 참이면 손패를 비우는
  후보를 뺀다(리드 후보가 전부 완주일 때만 예외).
- **danger**: 활성 상대 ≤2장 ∨ 활성 상대가 선언자 ∨ 가드.
- **isBoss**: 미공개 카드(56 − 내 손 − 공개 카드)로 같은 타입·길이에서 이길 수 없는 묶음.
  남의 폭탄은 보지 않는다. 용 단독은 (a) 때문에 불패로 친다.

**결정 지점**

| 지점 | 규칙 (위에서부터 먼저 맞는 것) |
| --- | --- |
| 그랜드티츄 (Dealing 8) | 파트너 미선언 ∧ power8(용 + 봉황 + A 수 + 2×포카드) ≥ 4 ∧ 용 또는 봉황 → 선언, 아니면 Ready |
| 티츄 — 패스 전 (Dealing 14) | 파트너 미선언 ∧ controls ≥ losers+2 ∧ losers ≤ 2 ∧ controls ≥ 3 |
| 티츄 — 패스 후 (Playing, 14장, 내 차례) | 파트너 미선언 ∧ 완주자 없음 ∧ 모든 상대 ≥10장 ∧ controls ≥ losers+1 ∧ losers ≤ 3 ∧ controls ≥ 3 ∧ 비컨트롤 묶음(groups − controls) < controls 이면서 ≤ 3. 상대가 선언했으면 앞의 두 문턱을 1씩 보수적으로. 선언은 차례를 넘기지 않는다(스케줄러가 같은 좌석을 다시 부른다) |
| 패스 — 파트너 | 파트너 선언 → 폭탄 밖 최강 카드 / 내가 선언(또는 패스 후 선언 기준 충족) → 최저 루저 단일 / 내 controls ≤1 → 최강 카드 / 그 외 → 비컨트롤 최고 단일 |
| 패스 — 상대 둘 | 남은 손에서 (cost(손−c), 점수카드 여부, 랭크) 최소를 차례로 2장. 약한 쪽이 toLeft(s+1). 용·봉황·폭탄 구성원·마작은 맨 뒤, 개는 파트너 미선언 ∧ 내 controls ≥2 일 때만 |
| 리드 L1 | 손패 전체가 한 합법 조합이면 낸다 (가드면 건너뜀) |
| 리드 L2 | 파트너 활성 ∧ (파트너 선언 ∨ 파트너 ≤2장 ∨ (내 controls=0 ∧ 파트너 장수 ≤ 내 장수) ∨ 가드) → 개 |
| 리드 L3 | 가드 아님 ∧ 묶음 2개 ∧ 그중 하나가 불패 → 불패 묶음부터 |
| 리드 L4 | 활성 상대가 1장 → 단일이 아닌 리드 중 cost 최소, 없으면 최고 단일 |
| 리드 L5 | 파트너 1장 → 최저 단일 |
| 리드 L6 | 가드 → 완주가 아닌 비컨트롤 최저 단일(파트너에게 리드를 넘김) |
| 리드 L7 | 폭탄·컨트롤을 뺀 후보 중 (cost(손−c), 랭크, −장수) 최소 → 컨트롤만 남으면 가장 약한 컨트롤 → 폭탄만 남으면 가장 약한 폭탄. 폭탄을 깨는 후보와 파트너가 나간 뒤의 개는 뺀다 |
| 팔로우 F0 | 용 양도 보류 → 양도(완주한 좌석이어도 이 분기가 먼저) |
| 팔로우 F1 | 손패를 비우는 합법 조합 (가드면 건너뜀) |
| 팔로우 F2 | 가드가 아닐 때, 활성 소원 랭크를 쥐고 그 랭크를 포함한 legal 이 있으면 그중 F4 순서로 최선(§9 자발 준수). 가드 중에는 파트너 보호가 먼저라 소원 랭크를 쥐고 있어도 패스할 수 있다 |
| 팔로우 F3 | top 이 파트너 → 패스. 단 s+1(마지막 응수자) 상대가 활성·미패스·≤2장이고 파트너 top 이 불패가 아니면, 이기는 불패 비폭탄 중 가장 싼 것으로 막는다. 파트너에게 폭탄은 쓰지 않는다 |
| 팔로우 F4 | 비폭탄 중 (Δcost, 컨트롤 사용, 랭크) 최소. 컨트롤은 트릭 점수 ≥15 ∨ danger ∨ 내가 선언자 ∨ 사용 후 묶음 ≤2 일 때만, 조합을 깨는 수는 트릭 점수 ≥10 ∨ danger 일 때만 |
| 팔로우 F5 | top 이 상대 ∧ (top 좌석 ≤3장 ∨ 활성 상대 선언자 ∨ 가드 ∨ 트릭 점수 ≥25 ∨ 폭탄 후 묶음 ≤1) ∧ F4 실패 → 이기는 가장 약한 폭탄 |
| 팔로우 F6 | 패스 |
| 소원 | 낸 카드에 마작이 있으면 14→2 순으로 내 손에 없고 4장이 다 나오지 않은 최고 랭크, 없으면 소원 없음 |
| 용 양도 | 활성 상대 중 장수가 많은 쪽(4등 가능성 → 그 트릭 점수가 1등 팀으로 넘어갈 수 있음), 동률이면 비선언자, 그다음 좌석 오름차순 |
| 봉황 | 조합 채우기는 플래너가 정한다. 단독 리드는 L7 의 "컨트롤만 남음"에서만, 단독 팔로우는 F4 컨트롤 조건 |

**봇이 전제하는 엔진 현황** — 위 §8.4·§9·§11 의 갭. 고치면(별도 D-NN) `isBoss` 와 F3·F4
문턱을 다시 보정한다.
- (a) 용이 top 이 되는 즉시 양도 대기가 걸려 아무도 응수할 수 없다 → 용 단독은 불패.
- (b) 용을 양도하면 활성 소원이 사라진다.
- (c) 한 번 패스하면 그 트릭에 다시 들어올 수 없다 → 팔로우는 "이번이 이 트릭의 마지막 기회".
- (d) 용으로 라운드가 끝나면 양도 없이 용 주인이 트릭을 가져간다.

**범위 밖**: 차례 밖 폭탄 인터럽트(BotScheduler 가 대기 좌석만 깨운다 — infra 무변경 원칙),
봇 난이도 방 옵션.

**코드:** `domain/game/tichu/bot/HeuristicBotPolicy.java`(결정 지점·공통 술어·튜닝 상수),
`BotView.java`, `HandPlanner.java`, `ComboFinder.java`, `LegalActionEnumerator.enumerateFull`,
`TichuPendingSeats.java`, 배선 `TichuGameEngine.botAction`.

**테스트:** `HeuristicBotPolicyTest`(결정 지점 시나리오), `ComboFinderTest`, `HandPlannerTest`,
`LegalActionEnumeratorTest`(enumerateFull), `BotDeterminismGuardTest`(소스 정적 가드),
`HeuristicBotSimulationTest`(순수 시뮬레이션 — 랜덤·그리디 기준선, 자가대전 선언, 숨김 정보
스크램블 동치, 결정 비용), `HeuristicBotEvaluationTest`(`MIRBOARD_BOT_EVAL=1` 대형 평가),
`TichuGameEngineBotActionTest`, `BotMatchSimulationIT`(실경로). 수치·보정 로그·재현 명령은
`docs/plans/tichu-bot-heuristic.md`.

---

## 본 문서 ↔ 코드 동기화 규칙

- 룰 변경 시 본 문서 + `docs/decisions.md` D-NN + 코드 + 테스트를 같은 commit 으로 묶는다.
- 본 문서의 "갭" 항목은 Phase 10B/10C/10D 진행 시 해소되거나 명시적으로 "보류 — 향후 작업" 으로 기록.
- ActionValidator / TichuEngine / ScoreCalculator 변경 시 본 문서의 해당 섹션 line 번호를 갱신 (코드 line shift 가능).

마지막 갱신: Phase 10A (D-56 참고).
