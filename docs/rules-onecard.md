# 원카드 룰 명세 (One Card Rules)

본 문서는 Mirboard 원카드 룰의 **단일 진실 공급원**이다. 코드(S2 순수 엔진, D-127)는 이 문서를 따르고,
어긋나면 코드를 고치거나 이 문서와 결정(`docs/decisions.md`)을 함께 고친다. 각 절 끝에 `rules-skullking.md` 와
같은 표기(**코드:** / **테스트:** / **갭:**)로 코드 위치와 테스트를 붙였다. 경로는
`server/src/{main,test}/java/com/mirboard/domain/game/onecard/` 기준이고, 위치는 줄 번호 대신 메서드 이름으로
적는다(줄 번호는 코드가 바뀌면 바로 틀린다). 포트 어댑터·저장·봇 정책·기록은 S3(D-128)에서 붙였다 — 서버 경로로
확인한 것은 각 절의 **코드(S3):** / **테스트(S3):** 다(인프라 테스트는 `server/src/test/java/com/mirboard/infra/` 기준).

> **출처와 하우스 룰.** 원카드는 모임마다 규칙이 크게 다르다. 이 문서는 D-123 에서 고른 **표준 기본형**만
> 다룬다. 원문이 답하지 않거나 갈리는 항목을 우리가 정한 것은 본문에 **[결정]** 으로 표시하고 §12 에 모았다.
> 설계 배경은 `docs/plans/onecard.md`.

---

## 1. 카드 (54장)

| 구분 | 장수 | 코드 표기 |
| --- | --- | --- |
| 무늬 카드 | 52 | 4무늬(♠ `SPADE` · ♥ `HEART` · ♦ `DIAMOND` · ♣ `CLUB`) × 13숫자 |
| 흑백 조커 | 1 | `Joker.BLACK` |
| 컬러 조커 | 1 | `Joker.COLOR` |

숫자는 A(1) · 2~10 · J(11) · Q(12) · K(13). 조커에는 무늬·숫자가 없다.

| 카드 | 분류 | 효과 |
| --- | --- | --- |
| 2 | 공격 | +2 |
| A | 공격 | +3 |
| 흑백 조커 | 공격 | +5 |
| 컬러 조커 | 공격 | +7 |
| J | 특수 | 다음 사람 건너뛰기 |
| Q | 특수 | 진행 방향 반전 |
| K | 특수 | 한 번 더 |
| 7 | 특수 | 무늬 지정 |
| 3·4·5·6·8·9·10 | 일반 | 없음 |

- **코드:** `card/Deck.java`(`SIZE=54`, `all()`), `card/PlayingCard.java`(`isAttack`·`attackValue`·
  `attackStrength`·`isSkip`·`isReverse`·`isExtraTurn`·`isSuitChange`·`isNormal`)
- **테스트:** `card/PlayingCardTest`(6건) — 54장, 공격 값, 세기 순서, 특수 역할(54장이 공격·J·Q·K·7·일반 중
  정확히 하나, 10/4/4/4/4/28), 일반 카드, 잘못된 카드 거절

## 2. 인원과 좌석

- 2~6명. 좌석 번호 0..n−1 은 방 참가자 순서다.
- 진행 방향의 초기값은 좌석 번호가 커지는 쪽(+1)이다.
- **[결정] 첫 차례는 서버가 무작위로 고른다.** 방장(좌석 0)에 고정하면 선 이점이 한 사람에게 쏠린다.
- **살아 있는 플레이어** = 탈락(§10)하지 않은 플레이어. 차례 계산과 "다음 사람"은 살아 있는 플레이어만 센다.

- **코드:** 첫 차례는 `OneCardEngine.startMatch`(주입한 난수), 살아 있는 좌석은
  `state/OneCardState.alive`·`aliveSeats`
- **테스트:** `OneCardEnginePlayTest.the_first_seat_is_random`

## 3. 분배와 시작

1. 54장을 섞어 각자 7장씩 나눈다.
2. 남은 카드가 뽑을 더미가 된다.
3. 뽑을 더미 맨 위 카드를 뒤집어 버린 더미의 첫 카드로 둔다.
4. **[결정] 첫 카드가 일반 카드(3·4·5·6·8·9·10)가 아니면** 그 카드를 뽑을 더미 맨 아래에 넣고 3 을
   되풀이한다. 첫 차례에 공격·특수 효과를 떠안는 불공정을 없앤다.
5. **[결정] 뽑을 더미의 카드를 모두 한 번씩 뒤집어 봐도 일반 카드가 없으면** 54장을 모두 거둬 1 부터 다시
   한다. 일반 카드 28장이 모두 손패로 가면(6인에서 드물게) 4 만으로는 끝나지 않기 때문이다.

6인이면 42장을 나누고 시작 카드 1장을 뒤집어 뽑을 더미에 11장이 남는다.

- **코드:** `Dealer.deal`(시작 카드가 일반 카드가 아니면 맨 아래로 보내고 다시 뒤집기, 더미를 다 봐도 없으면
  처음부터 다시 나누기). 셔플은 `Dealer.Shuffler` 로 주입한다
- **테스트:** `DealerTest`(8건) — 2~6인 분배, 맨 아래로 보내고 다시 뒤집기, 다시 나누기(§3-5 — 두 번째 분배의 손패·
  시작 카드·54장 중복 없음까지 보고, 5초 안에 끝나야 한다), 인원 범위

## 4. 차례에 할 수 있는 것

자기 차례에는 **카드 1장 내기**(§5) 또는 **먹기**(§7) 중 하나를 한다.

- **[결정] 낼 수 있어도 먹을 수 있다**(전략적 먹기).
- 턴 제한이 있는 방에서 시간을 넘기면 먹기를 한 것으로 본다.

- **코드:** `OneCardEngine.apply`(`PLAY_CARD`·`DRAW`), 시간 초과는 `OneCardEngine.timeoutAction`(먹기),
  봇·검증용 목록은 `OneCardEngine.legalActions`
- **테스트:** `OneCardEnginePlayTest`(29건 — §2~§8·§10~§11 의 시작·내기·먹기·파산·종료) —
  `drawing_takes_one_card_even_when_a_card_could_be_played`,
  `the_timeout_action_is_drawing_for_the_seat_on_turn_only`, `legal_actions_*`·`under_attack_*`
- **코드(S3):** 시간 초과는 `infra/bot/TurnTimeoutScheduler` 가 포트의 `timeoutAction` 으로 적용한다.
- **테스트(S3):** `bot/OneCardBotMatchSimulationIT.an_idle_human_is_carried_by_turn_timeouts_until_the_match_ends`

## 5. 내기

### 5.1 기준

- **맨 위 카드** = 버린 더미의 맨 위.
- **기준 무늬** = 7 로 지정된 무늬가 있으면 그 무늬, 없으면 맨 위 카드의 무늬.

### 5.2 공격받는 중이 아닐 때

다음 중 하나면 낼 수 있다.

1. 기준 무늬와 같은 무늬.
2. 맨 위 카드와 같은 숫자. **[결정] 7 위의 7 도 포함한다** — 무늬가 지정된 뒤에도 숫자 일치 규칙은 그대로다.
3. 조커(언제나).
4. **[결정] 맨 위가 조커이고 공격받는 중이 아니면 아무 카드나.** 조커에는 무늬가 없다. 공격이 먹기로
   끝났든 받을 사람의 탈주로 사라졌든(§10, §9.2) 같다.

**[결정] 7 은 와일드가 아니다** — 다른 카드와 같은 조건(1·2, 맨 위가 조커면 4)을 따른다. 7 을 아무 데나
내게 하는 하우스 룰은 쓰지 않는다.

### 5.3 공격받는 중일 때

반격 조건(§6.2)을 지키는 공격 카드만 낼 수 있다. **[결정] 일반 카드와 7·J·Q·K 는 낼 수 없다**(반격 수단이 아니다).

### 5.4 내고 나면 (순서대로)

1. 카드 효과를 적용한다 — 공격은 §6, 특수는 §8.
2. 손패가 0장이면 즉시 매치를 끝낸다(§11). 마지막 카드가 특수·공격 카드여도 인정한다 **[결정]**.
3. 손패가 정확히 1장이면 외치기 경쟁(§9)이 먼저 열린다.
4. 다음 차례를 정한다(§8.2).

- **코드:** `rules/PlayRules.canPlay`(§5.2·§5.3), 기준 무늬 `state/OneCardState.baseSuit`, 내기 처리
  `OneCardEngine.play`(§5.4 순서 — 효과, 0장이면 종료, 1장이면 경쟁, 다음 차례)
- **테스트:** `rules/PlayRulesTest`(9건), `OneCardEnginePlayTest`(7 의 무늬 지정, 거절 사유)

## 6. 공격과 반격

### 6.1 공격

공격 카드를 내면 **공격 누적**에 그 값을 더하고, 다음 사람이 "공격받는 중"으로 차례를 받는다.

### 6.2 반격

공격받는 사람은 공격 카드로 반격해 누적을 다음 사람에게 넘길 수 있다.

- **[결정] 세기**: 2 < A < 흑백 조커 < 컬러 조커. 맨 위 공격 카드와 **같거나 센** 카드만 낼 수 있다.
- **일치**: 같은 숫자는 무늬와 무관하다. 다른 숫자(2 위의 A)는 기준 무늬가 같아야 한다. 조커는 무늬와 무관하다.

| 맨 위 공격 | 반격할 수 있는 카드 |
| --- | --- |
| 2 | 모든 2 · 기준 무늬의 A · 조커 2장 |
| A | 모든 A · 조커 2장 |
| 흑백 조커 | 컬러 조커 |
| 컬러 조커 | 없음 |

### 6.3 받기

반격하지 않으면(못 하거나 안 하거나) 누적 장수만큼 먹는다(§7). 누적은 0 이 되고 공격이 끝나며, 먹은 사람의
차례도 끝난다.

- **코드:** 값·세기 `card/PlayingCard.attackValue`·`attackStrength`, 반격 `rules/PlayRules.canCounter`, 누적은
  `OneCardEngine.play`, 받기는 `OneCardEngine.draw`
- **테스트:** `rules/PlayRulesTest`(§6.2 표의 네 행),
  `OneCardEnginePlayTest.an_attack_passes_to_the_next_seat_which_must_counter_or_draw_the_stack`

## 7. 먹기

- 공격받는 중이 아니면 1장, 공격받는 중이면 누적 장수만큼 먹는다.
- **[결정] 먹으면 차례가 끝난다** — 먹은 카드는 이번 차례에 낼 수 없다.
- 뽑을 더미가 모자라면 §7.1 로 채운다. 그래도 모자라면 있는 만큼만 먹는다(0장일 수 있다) **[결정]**.
- 먹은 뒤 손패가 20장 이상이면 파산이다(§10).

### 7.1 뽑을 더미 다시 채우기

뽑을 더미가 비었는데 더 먹어야 하면 버린 더미의 맨 위 1장만 남기고 나머지를 섞어 뽑을 더미로 만든다. 맨 위
카드와 그 상태(지정 무늬·공격 누적)는 그대로다.

- **코드:** `OneCardEngine.draw`, 다시 채우기는 `OneCardEngine.drawFromPile`(맨 위만 남기고 섞기, 그래도
  모자라면 있는 만큼만)
- **테스트:** `OneCardEnginePlayTest` — `drawing_takes_one_card_even_when_a_card_could_be_played`,
  `an_empty_pile_is_refilled_from_the_discard_pile_keeping_the_top`,
  `an_attack_larger_than_what_is_left_draws_only_what_is_there_and_is_not_a_pass`(누적이 더미보다 많을 때),
  `with_nothing_left_to_draw_the_turn_is_a_pass_and_everyone_passing_is_a_stalemate`.
  벌칙 먹기의 더미 채우기는 §9 의 `a_catch_with_an_empty_pile_*`

## 8. 특수 카드와 차례 순서

### 8.1 효과 (공격받는 중이 아닐 때만 낼 수 있다)

- **J** — 다음 사람을 건너뛴다.
- **Q** — 진행 방향을 뒤집는다.
- **K** — 같은 사람이 한 번 더 한다(내기 또는 먹기). K 를 이어 내면 계속 이어진다.
- **7** — 낼 때 무늬 하나를 지정한다(7 자신의 무늬도 가능). 다음 카드가 놓일 때까지 기준 무늬가 된다.
- **[결정] 살아 있는 플레이어가 2명이면 J·Q 도 "한 번 더"로 동작한다.** J 는 상대를 건너뛰면 저절로 자기
  차례가 된다. Q 는 방향만 바꾸면 여전히 상대 차례라, 2인 관례(우노의 2인 리버스)를 따라 한 번 더로 둔다.
  Q 는 이때도 방향 값을 뒤집는다(표시용 — 2명뿐이라 순서에는 영향이 없다).

### 8.2 다음 차례

| 방금 낸 카드 | 다음 차례 |
| --- | --- |
| 일반·7 | 현재 방향의 다음 살아 있는 플레이어 |
| 공격 카드 | 다음 살아 있는 플레이어 — "공격받는 중"으로 받는다 |
| J | 다음 살아 있는 플레이어를 건너뛴 그다음 |
| Q | 방향을 뒤집은 뒤 그 방향의 다음 살아 있는 플레이어 |
| K (2인이면 J·Q 도) | 같은 사람 |
| (먹기) | 현재 방향의 다음 살아 있는 플레이어 |

- **코드:** `rules/TurnOrder.afterPlay`(§8.2 표), `rules/TurnOrder.nextAlive`(탈락 건너뛰기), Q 의 방향
  반전은 `OneCardEngine.play`
- **테스트:** `rules/TurnOrderTest`(8건 — §8.2 표의 모든 행과 2인), `OneCardEnginePlayTest`(J·Q·K·2인,
  Q 가 낸 뒤 방향을 `CARD_PLAYED` 에 싣는 것)

## 9. 외치기 경쟁 ("원카드!" / "잡기!")

1. 카드를 낸 결과 손패가 **정확히 1장**이면 경쟁 창이 열린다. 어떤 카드를 냈든(공격·특수·K 포함) 같다.
2. 창은 **3초**다. 창이 열린 동안에는 아무도 내거나 먹을 수 없다. 턴 제한 시계는 창이 닫힌 뒤 다시 시작한다.
3. 1장 남은 사람(**주인**)은 "원카드!", 살아 있는 다른 플레이어는 "잡기!"를 누를 수 있다. 관전자와
   탈락자는 누를 수 없다.
4. **첫 누름이 창을 닫는다.** "먼저"는 서버에 먼저 도착해 처리된 요청이다. **[결정]** 창 끝이나 봇이 누를
   시각 직후(타이머 폴링·락 대기로 최대 수백 ms)에 처리된 누름도 인정한다(D-128).

   | 결과 | 조건 | 효과 |
   | --- | --- | --- |
   | `CALLED` | 주인이 먼저 | 없음 |
   | `CAUGHT` | 다른 사람이 먼저 | 주인이 벌칙으로 1장 먹는다(§9.1) |
   | `EXPIRED` | 3초 동안 아무도 안 누름 | 없음 |

5. 창마다 번호가 있고, 지금 창과 번호가 다른 누름은 거절한다(늦게 도착한 누름이 다음 창에 붙지 않도록).
6. 봇도 사람처럼 반응 시간을 두고 누른다(창마다 추첨, 기본 1.0~2.5초).
7. 창이 열린 동안 탈주가 생기면 창은 벌칙 없이 닫힌다.
8. **[결정]** 창이 닫히면 §5.4 의 다음 차례 결정을 이어 간다(K 면 주인이 다시, 공격이면 다음 사람이 공격받는 중으로).
   창이 열린 동안 탈주가 있었다면 §9.2 를 따른다.

### 9.1 벌칙 먹기

**[결정]** 잡혀서 먹는 1장은 차례의 먹기(§7)와 다르다. 뽑을 더미 채우기(§7.1)와 "있는 만큼만"만 따르고, 차례를
끝내지 않으며(K 면 주인이 계속, J 의 건너뛰기도 그대로) 공격 누적을 바꾸지 않는다. 차례 수(§11.3)와 연속 패스
수에도 넣지 않는다.

### 9.2 창이 열린 동안의 탈주

**[결정]** 창이 열린 동안에는 차례인 사람이 없다. 다음 차례를 받을 사람(과 공격 여부)은 **카드를 낸 순간** 그때의
살아 있는 인원으로 §8.2 를 따라 정해 두고, 창이 닫히면 그 사람에게 넘긴다. 그사이 누가 탈주하면 창은 벌칙 없이
닫힌다(§9-7). 주인이 탈주해도 정해 둔 다음 차례는 그대로다(K 였다면 정해 둔 사람이 주인 자신이므로 아래 규칙대로
넘어간다). 정해 둔 사람이 창 중에 탈주했다면 같은 방향으로 그다음 살아 있는 플레이어가 차례를 받고, 그 차례에
걸려 있던 공격은 사라진다(§10 과 같은 이유).

- **코드:** 창 열기 `OneCardEngine.openRace`(슬롯·지터, 봇 추첨 `fastestBot`), 누름 `OneCardEngine.press`,
  시간 전이 `OneCardEngine.timerDeadline`·`onTimer`, 해소·벌칙 `OneCardEngine.resolveRace`, 설정 `RaceSettings`
  (`DEFAULT` — 창 3초, 봇 1.0~2.5초, 슬롯 8), 창 상태 `state/RaceWindow`
- **테스트:** `OneCardEngineRaceTest`(17건 — 세 결과, 벌칙 뒤 차례(§9.1), K·J·공격, 창 번호 검사, 봇 반응) 중
  새로 더한 것: `being_caught_after_an_attack_card_keeps_the_stack_for_the_reserved_seat`(잡혀도 공격 누적 유지),
  `the_skipped_seat_may_catch_and_the_jack_still_skips_it`, `of_several_bots_only_the_fastest_press_is_kept`,
  `a_catch_with_an_empty_pile_reshuffles_the_discards_and_keeps_the_top_and_the_declared_suit`,
  `an_eliminated_bot_does_not_press_so_the_window_runs_its_full_length`.
  창 중 탈주(§9.2)는 `OneCardEngineDesertionTest`
- **코드(S3):** 엔진 타이머(D-128) — 어댑터 `OneCardGameEngine.timer`(창 끝 또는 추첨된 봇 시각까지 남은 시간)·
  `onTimer`, 무장 `infra/bot/TurnTimeoutScheduler`, 발화 `infra/bot/EngineTimerScheduler`. 창 길이·봇 반응 구간은
  `mirboard.onecard.*` 설정(`OneCardGameDefinition`).
- **테스트(S3):** `bot/OneCardRaceIT`(6건 — 주인이 먼저 `CALLED`, 사람이 잡음 `CAUGHT`, 창 안에 반응한 봇이 잡음,
  아무도 안 누르면 엔진 타이머가 `EXPIRED` 로 닫음, 창 중 탈주 `CANCELLED`, 닫힌 창의 누름 거절)

## 10. 파산과 탈락

- **파산**: 먹은 뒤 손패가 20장 이상이면 탈락한다. 손패는 섞지 않고 뽑을 더미 맨 아래에 넣는다. 벌칙(§9)으로는
  파산할 수 없다 — 1장에서 2장이 될 뿐이다.
- **탈주**: 게임 중 나가기와 끊김 유예 초과는 파산과 같이 탈락으로 처리한다. 손패는 뽑을 더미 맨 아래로 간다.
  **[결정] 이미 탈락한 사람(파산·탈주)이 나가는 것은 탈주로 보지 않는다** — 순위와 상태가 그대로다(파산자가
  나가도 파산 순위를 지키고 탈주 기록이 붙지 않는다).
- 탈락자는 차례에서 빠진다. 탈락자의 차례였다면 다음 살아 있는 플레이어에게 넘어간다.
- **[결정] 탈주자의 차례에 걸려 있던 공격은 사라진다**(누적 0). 다른 사람의 탈주로 다음 사람이 공격을 떠안지
  않게 한다. 공격 누적을 먹고 파산했다면 그 공격은 이미 끝났다.

- **코드:** 파산은 `OneCardEngine.draw` → `eliminate`(손패를 섞지 않고 더미 맨 아래로), 탈주는
  `OneCardEngine.desert`(이미 끝났거나 이미 탈락한 좌석이면 `NOT_APPLICABLE`)
- **테스트:** `OneCardEnginePlayTest.reaching_twenty_cards_is_bankruptcy_and_two_players_leaves_one_standing`
  (손패 순서·이벤트 순서·버전까지), `a_bankruptcy_among_three_humans_passes_the_turn_on_and_the_match_goes_on`,
  `OneCardEngineDesertionTest`(15건) 중 새로 더한 것:
  `out_of_range_seats_and_a_seat_that_just_left_are_not_a_desertion`,
  `a_bystander_deserting_leaves_the_pending_attack_and_the_turn_alone`,
  `the_owner_deserting_during_an_attack_race_still_passes_the_attack_to_the_reserved_seat`,
  `if_the_seat_a_jack_skipped_to_deserts_during_the_race_the_next_live_seat_plays`,
  `deserting_passes_the_turn_on_in_the_reversed_direction_too`
- **테스트(S3):** `bot/OneCardRaceIT.a_desertion_during_the_race_closes_it_without_penalty_and_the_reserved_seat_plays`
  (탈주 서비스 경로)

## 11. 종료와 순위

### 11.1 종료 조건 (즉시, 위에서부터 판정)

| 사유 | 조건 |
| --- | --- |
| `FINISHED` | 누군가 마지막 카드를 냈다 |
| `LAST_STANDING` | 탈락으로 살아 있는 플레이어가 1명 |
| `NO_HUMANS` | 살아 있는 플레이어 중 사람(봇이 아닌)이 없다 |
| `STALEMATE` | 교착 또는 차례 상한(§11.3) |

### 11.2 순위

1. `FINISHED` 면 다 낸 사람이 1등이고, 나머지 살아 있는 플레이어는 남은 장수가 적은 순이다.
2. 그 밖의 사유면 살아 있는 플레이어 전원을 남은 장수가 적은 순으로 매긴다.
3. 그 아래 파산자 — 늦게 파산한 쪽이 위다.
4. 맨 아래 탈주자 — 탈주자끼리는 동순위다.

- 장수가 같으면 동순위다. **[결정] 동순위 다음 순위는 건너뛴다**(1, 1, 3).
- **승리는 1등이다**(동순위면 모두).

### 11.3 교착과 차례 상한

- **[결정] 패스** = 먹기를 했는데 더미가 비어 실제로 먹은 장수가 0 인 차례. 낼 수 있는 카드가 있어도 먹기를
  골라 0장을 먹었다면 패스다(전원이 그렇게 버티면 끝내는 쪽을 택했다).
- 살아 있는 전원이 연달아 패스하면 교착이다 → `STALEMATE`.
- 누군가 카드를 내거나 1장 이상 먹으면 연속 패스 수는 0 으로 돌아간다. 탈락(탈주 포함)이 생겨도 0 으로
  돌아간다 — 남은 사람끼리 전원이 다시 연달아 패스해야 교착이다.
- **[결정] 총 차례 상한 600**: 내기·먹기 1회를 1차례로 센다(경쟁 창의 누름은 차례가 아니다). 600 에 닿으면
  `STALEMATE`. 실제 판은 수십~백여 차례라 안전장치다.

- **코드:** 종료 판정 `OneCardEngine.endIfDecided`(LAST_STANDING·NO_HUMANS)·`finish`, 교착·상한은
  `OneCardEngine.draw`·`play`, 순위 `rules/Ranking.rank`, 결과 `state/MatchResult`
- **테스트:** `rules/RankingTest`(4건), `OneCardEnginePlayTest`(FINISHED, STALEMATE 두 경로) — 판정 순서는
  `finishing_with_the_last_card_on_the_turn_limit_is_a_win_not_a_stalemate`(FINISHED 가 상한보다 먼저),
  `drawing_on_the_turn_limit_ends_as_a_stalemate_without_announcing_another_turn`,
  `bankruptcy_on_the_turn_limit_ends_as_last_standing_not_a_stalemate`(LAST_STANDING 이 상한보다 먼저),
  `a_bot_left_alone_by_a_bankruptcy_is_last_standing_not_no_humans`(LAST_STANDING 이 NO_HUMANS 보다 먼저),
  `when_a_bankruptcy_leaves_only_bots_the_match_ends_as_no_humans_with_the_bankrupt_seat_last`, 연속 패스 초기화는
  `playing_or_drawing_a_card_resets_the_pass_streak`, `OneCardEngineDesertionTest`(LAST_STANDING, NO_HUMANS,
  연속 패스 초기화) 중 `the_last_standing_bot_beats_no_humans_when_a_human_deserts_a_two_seat_table`
- **코드(S3):** 매치 기록 `persistence/OneCardMatchRecorder`(V12 `onecard_match_results`/`participants`) — 승리는 1등
  전원(동순위 포함), 개인전 ELO 점수는 `좌석 수 − 순위`, 탈주 좌석은 최하위·desert_count, 봇·게스트가 낀 매치는
  ELO 제외. 어댑터 `OneCardGameEngine.advance` 가 매치를 끝낸 전이에서만 한 번 발행한다.
- **테스트(S3):** `persistence/OneCardMatchRecorderIT`(4건), `bot/OneCardBotMatchSimulationIT`(4건 — 2·4·6인 봇 완주와
  손을 놓은 사람이 낀 판, 방 FINISHED + 기록 1행)

## 12. 우리가 정한 것 (미규정·하우스 룰 선택)

| # | 항목 | 결정 | 이유 |
| --- | --- | --- | --- |
| 1 | 첫 차례 | 서버 무작위 | 방장 선 고정 방지 |
| 2 | 시작 카드 | 일반 카드가 나올 때까지 다시 뒤집기(더미 맨 아래로), 더미를 다 봐도 없으면 처음부터 다시 나누기 | 첫 차례 불공정 제거, 반드시 끝남 |
| 3 | 전략적 먹기 | 허용 | 손패 관리 선택지 |
| 4 | 먹은 직후 내기 | 불가(차례 종료) | 단순하고 흔한 규칙 |
| 5 | 7 위의 7 | 허용(같은 숫자) | 숫자 일치 규칙 일관성 |
| 6 | 조커 뒤 | 공격받는 중이 아니면 아무 카드나(먹기로 끝났든 탈주로 사라졌든) | 조커에는 무늬가 없다 |
| 7 | 공격 세기·반격 | 2<A<흑백<컬러, 같거나 센 카드, 다른 숫자는 무늬 일치 | §6.2 표 |
| 8 | 공격 중 특수 카드 | 불가 | 반격 수단이 아니다 |
| 9 | 2인 J·Q | "한 번 더"(Q 는 방향 값만 뒤집음) | J 는 저절로, Q 는 2인 관례 |
| 10 | K 로 1장 | 경쟁 먼저, 해소 뒤 같은 사람 계속 | §9-8 |
| 11 | 특수·공격 카드로 끝내기 | 허용 | 단순 |
| 12 | 누적이 더미보다 많음 | 있는 만큼만 | §7 |
| 13 | 탈주자 차례의 공격 | 사라짐(창 중 받을 사람의 탈주 포함, §9.2) | 남의 탈주 피해 방지 |
| 14 | 동순위 | 경쟁 순위(1, 1, 3) | 흔한 표기 |
| 15 | 차례 상한 | 600 → `STALEMATE` | 무한 진행 방지 |
| 16 | 경쟁 창 | 3초, 첫 누름, 벌칙 1장 | D-123 |
| 17 | 7 내기 조건 | 와일드 아님 — 다른 카드와 같은 조건(§5.2) | 설계서 §3.2-6 |
| 18 | 패스(교착) | 0장 먹기면 낼 카드가 있어도 패스, 탈락이 생기면 연속 수 0 | 버티기로 끝나지 않는 판 방지 |
| 19 | 탈락 뒤 나가기 | 탈주 아님 — 순위·상태 그대로 | 이미 끝난 사람에게 이중 처분 없음 |
| 20 | 벌칙 먹기 | 차례·공격 누적·차례 수에 영향 없음 | §9.1 — K·J 효과와 충돌 방지 |
| 21 | 창 중 탈주 | 다음 차례는 낸 순간 정해 둠 — 그 사람이 나가면 그다음 사람이 공격 없이 받음 | §9.2 |
| 22 | 늦게 처리된 누름 | 인정 — 서버가 먼저 처리한 누름이 이김 | 네트워크 지연에 관대, 봇 반응 구간에 흡수 (D-128) |

## 13. 범위 밖 (v1)

♠A 특례, 3 방어, 폭탄, 계단(연속 내기), 같은 숫자 동시 내기, 마지막 카드 제한, 리매치, 내기 칩, 방 옵션으로
하는 하우스 룰 선택.

## 14. 검증 기준 (S2)

- **카드 보존**: 매 전이 뒤 뽑을 더미 + 버린 더미 + 살아 있는 플레이어 손패 = 54장(탈락자 손패는 더미로
  옮겨졌으므로 0).
- 공격 누적 ≥ 0 이고, 공격받는 중이 아니면 누적 = 0. 단, 공격 카드로 열린 경쟁 창 동안은 누적이 정해 둔 다음
  사람(§9.2)을 기다린다.
- 2~6인 무작위 봇 시뮬레이션 인원별 2,000판이 전부 끝난다(차례 상한 도달 빈도를 기록한다).
- 연속 패스 도중 탈주가 끼면 연속 패스 수가 0 으로 돌아가는지(§11.3) 테스트한다.
- §6.2 표의 모든 칸, §8.2 표의 모든 행(2인 포함), §9 의 세 결과와 §9.1·§9.2(벌칙 뒤 차례, 창 중 주인·공격
  대상 탈주), §11.1 의 네 사유마다 단위 테스트가 있다.
- 시작 카드 다시 나누기(§3-5)가 실제로 끝나는지 확인한다. 셔플을 주입해 첫 분배는 일반 카드를 모두 손패로
  보내고 다시 나눌 때는 다른 순서를 주는 테스트로 본다(같은 순서를 되풀이하면 같은 실패가 반복된다).

- **코드:** `invariant/OneCardInvariantChecker`(카드 보존, 공격 누적·지정 무늬와 맨 위 카드, 차례 좌석, 그리고
  종료 판정이 빠지지 않았는지 — 열린 판은 생존자 2명 이상 · 연속 패스가 생존자 수 미만 · 차례 상한 미만 · 살아 있는
  좌석의 손패가 비지 않음 · 창이 예약한 좌석이 살아 있음, 끝난 판은 `FINISHED` ⇔ 손패를 비운 생존자 ·
  `LAST_STANDING` ⇒ 생존자 1명 · `NO_HUMANS`·`STALEMATE` ⇒ 생존자 2명 이상 · `STALEMATE` ⇒ 전원 연속 패스 또는 차례
  상한, 그리고 차례 수는 어느 때나 상한 이하)
- **테스트:** `invariant/OneCardInvariantCheckerTest`(23건 — 통과 1건 + 고의 위반 22건: 카드 보존·탈락자 손패·공격
  누적·지정 무늬·방향·20장 생존자·차례 좌석·끝난 판의 차례/창, 그리고 위 종료 판정 규칙마다 하나. 새 규칙과 분기는
  하나씩 끄면 해당 케이스만 실패한다), `OneCardJsonRoundTripTest`(5건 — 카드·상태·액션 JSON 왕복, 이벤트 11종의 envelope 이름·비공개
  라우팅·키 집합), `OneCardMatchSimulationTest`(10건 — 인원별 2,000판 + 사람 1·봇 300판, 매 전이 불변식 검사와
  이벤트만 듣는 클라이언트가 그린 테이블(공개 이벤트 + 비공개 손패)과 엔진 상태의 대조). 차례 상한 도달은
  2인 1% 미만 · 3인 1.0% · 4인 2.1% · 5인 2.3% · 6인 5.1%다(전략 없는 무작위 판, 시드 고정 — 테스트는 10% 미만을
  요구한다). 탈락자 손패를 더미로 옮기지 않는 고의 결함을 넣으면 10건이 모두 실패한다(엔진 `eliminate` 의
  `t.drawPile.addAll(hand)` 를 지워 확인).
- **합계:** 원카드 도메인 테스트 134건 — `PlayingCardTest` 6 + `DealerTest` 8 + `PlayRulesTest` 9 + `TurnOrderTest` 8 +
  `RankingTest` 4 + `OneCardEnginePlayTest` 29 + `OneCardEngineRaceTest` 17 + `OneCardEngineDesertionTest` 15 +
  `OneCardJsonRoundTripTest` 5 + `OneCardInvariantCheckerTest` 23 + `OneCardMatchSimulationTest` 10. 전부 Docker 가
  필요 없다.
