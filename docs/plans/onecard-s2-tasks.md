# 원카드 S2 구현 계획 — 순수 룰 엔진 (D-127)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 원카드 룰 정본(`docs/rules-onecard.md`)을 그대로 따르는 순수 룰 엔진을 `domain.game.onecard` 에 만든다 —
저장소·시계·Spring 없이 단위 테스트와 2~6인 무작위 시뮬레이션으로 검증된다.

**Architecture:** 스컬킹과 같은 2계층의 아래층(S3 가 포트 어댑터를 얹는다). 상태는 불변 레코드 하나
(`OneCardState`)에 경쟁 창(`race`)·결과(`result`)를 nullable 로 붙이고 단계는 파생한다. 엔진은 한 전이
동안만 가변 사본(`Table`)을 고쳐 새 레코드로 얼린다. 시각은 경쟁 창을 여는 순간만 `now` 인자로 받고, 창이
저절로 닫히는 시각(`timerDeadline`)과 그때의 전이(`onTimer`)는 엔진이 계산한다. 난수는 생성자로 주입한다.

**Tech Stack:** Java 25(record·sealed·switch 패턴), Jackson 2 어노테이션(`@JsonTypeInfo` 등), JUnit 5 + AssertJ.

**참고:** 설계 `docs/plans/onecard.md`(§4, §6 의 S2 행) · 룰 정본 `docs/rules-onecard.md`(D-125) · 앞 단계 계획
`docs/plans/onecard-s0-s1-tasks.md`. 이 계획의 코드는 검증용 스파이크에서 옮겼고, 계획서만으로 깨끗한 체크아웃에서
Task 1~9 를 다시 따라 해 각 단계의 실패·통과와 누적 테스트 수(아래 Global Constraints)가 계획대로임을 확인했다.

## Global Constraints

- 응답·주석·문서는 한국어.
- 작업 위치: 브랜치 `feat/onecard-s2`, 워크트리 `/Users/yupchang/Developer/mirboard/.claude/worktrees/onecard-plan`
  (최신 main 기반). 메인 체크아웃(`/Users/yupchang/Developer/mirboard`)은 다른 세션이 쓰므로 건드리지 않는다.
  Bash 는 매 호출 작업 폴더가 바뀔 수 있으니 **명령마다 워크트리 절대경로로 `cd` 하거나 `git -C` 를 쓴다.**
- 커밋 메시지 끝 줄: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` — 모델이 무엇이든 이 줄 그대로.
- 커밋 때 pre-commit 훅이 `scripts/check.sh fast`(클라 tsc + vitest + 서버 컴파일)를 돈다. `--no-verify` 금지.
- **이 단계는 순수 도메인만 만든다.** `domain.game.onecard` 는 `domain.game.core`(`GameContext`·`GameState`·
  `GameAction`·`GameEvent`·`GameActionRejectedException`)와 Jackson 어노테이션에만 기댄다. `@Component`·Redis·
  Spring·인프라 변경 없음 — 포트 어댑터·봇 정책·기록기·저장은 S3.
- 룰의 기준은 `docs/rules-onecard.md`(D-125) 절 번호다. 코드 주석의 `§N` 은 그 문서를 가리킨다.
- 결정 번호: **D-127**(D-126 은 티츄 재동기화 세션이 쓰는 중). 새 결정은 `docs/decisions.md` 의 `## D-125` 바로
  위에 넣는다. 착수 시점에 D-127 도 쓰였으면 다음 빈 번호로 바꾸고 본문의 번호를 모두 고친다.
- 원카드 테스트 수(누적): Task 1 → 6, Task 2 → 14, Task 3 → 19, Task 4 → 40, Task 5 → 66, Task 6 → 78,
  Task 7 → 87, Task 8 → 97. 원카드 테스트 전체는 Docker 없이 5초 안팎이다.
- **편집 표기**: ```` ```diff ```` 블록은 `-` 줄을 찾아 지우고 `+` 줄을 넣는다. 공백으로 시작하는 줄은 위치를
  잡는 문맥이다. 세 경우 모두 첫 글자 하나를 떼고 읽는다(나머지 들여쓰기는 파일 그대로다). `+` 뒤가 비어 있으면
  빈 줄을 넣는다.

## 파일 지도

| 파일 (`server/src/main/java/com/mirboard/domain/game/onecard/` 기준) | 태스크 | 책임 |
| --- | --- | --- |
| `card/Suit.java` · `card/Joker.java` · `card/PlayingCard.java` · `card/Deck.java` | 1 | 카드·역할·공격 값과 세기(§1) |
| `Dealer.java` | 2 | 분배·시작 카드(§3), 셔플러 주입 |
| `state/Elimination.java` · `state/MatchResult.java` · `state/RaceWindow.java` · `state/OneCardState.java` | 3 | 상태 레코드 |
| `action/OneCardAction.java` · `action/RejectionReason.java` · `action/OneCardActionRejectedException.java` | 3 | 클라 액션·거절 사유 |
| `event/OneCardEvent.java` | 3 | 이벤트(공개·비공개 손패) |
| `rules/PlayRules.java` · `rules/TurnOrder.java` · `rules/Ranking.java` | 4 | §5·§6 / §8.2 / §11.2 |
| `OneCardEngine.java` | 5·6·7 | 순수 엔진 — 시작·내기·먹기·종료(5), 경쟁 창(6), 탈주(7) |
| `invariant/OneCardInvariantChecker.java` | 5 | §14 불변식 |
| `RaceSettings.java` | 6 | 경쟁 창·봇 반응 시간 설정 |
| 테스트(`server/src/test/java/com/mirboard/domain/game/onecard/`) | 1~8 | 태스크마다 아래 |
| `docs/*`, `scripts/check.sh`, `CLAUDE.md`, `README.md` | 9 | 룰↔코드 매핑·수치·명령 |

---
## S2 — 원카드 순수 룰 엔진 (D-127)

### Task 1: 카드 모델 + D-127 기록

**Files:**
- Create: `server/src/main/java/com/mirboard/domain/game/onecard/card/Suit.java`, `card/Joker.java`, `card/PlayingCard.java`, `card/Deck.java`
- Create: `server/src/test/java/com/mirboard/domain/game/onecard/card/PlayingCardTest.java`
- Modify: `docs/decisions.md` (`## D-125` 바로 위)

**Interfaces:**
- Produces: `enum Suit { SPADE, HEART, DIAMOND, CLUB }`, `enum Joker { BLACK, COLOR }`,
  `record PlayingCard(Suit suit, int rank, Joker joker)` — `of(Suit, int)`, `joker(Joker)`, 상수 `ACE=1`·`JACK=11`·
  `QUEEN=12`·`KING=13`, `isJoker()`·`isAttack()`·`attackValue()`·`attackStrength()`·`isSkip()`·`isReverse()`·
  `isExtraTurn()`·`isSuitChange()`·`isNormal()`. `final class Deck` — `SIZE=54`, `all()`(고정 순서 54장).

- [ ] **Step 1: D-127 결정 기록**

`docs/decisions.md` 에서 `## D-125 (2026-10-04)` 줄 바로 위에 다음을 넣는다(뒤에 빈 줄 하나).

```markdown
## D-127 (2026-10-04) — 원카드 순수 룰 엔진 (원카드 S2, 신규 패키지)

`domain.game.onecard` 에 저장소·시계 없는 순수 엔진을 둔다(스컬킹과 같은 2계층의 아래층, 어댑터는 S3). 상태는
단계별 sealed 타입 대신 레코드 하나(`OneCardState`)에 경쟁 창·결과를 nullable 로 붙였다 — 세 단계가 같은 테이블을
공유하기 때문이고, 한 전이가 여러 필드를 바꾸므로 엔진 안에서만 가변 사본을 고쳐 새 레코드로 얼린다. 시각은 경쟁
창을 여는 순간만 `now` 인자로 받고, 창이 저절로 닫히는 시각(`timerDeadline`)과 그때의 전이(`onTimer`)를 엔진이
계산한다 — 봇 반응 시간은 창을 열 때 추첨해 가장 빠른 한 명만 상태에 남기고 이벤트에는 싣지 않는다. 비공개 손패
이벤트는 손패 전체와 `handVersion` 을 싣는 `HAND_UPDATED` 하나로 통일해 설계서의 `CARDS_RECEIVED` 를 대신했다.
검증은 매 전이 불변식 검사와 2~6인 무작위 시뮬레이션(인원별 2,000판)이다.
```

- [ ] **Step 2: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/card/PlayingCardTest.java`:

```java
package com.mirboard.domain.game.onecard.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** `docs/rules-onecard.md` §1 — 카드와 역할. */
class PlayingCardTest {

    private static final PlayingCard BLACK = PlayingCard.joker(Joker.BLACK);
    private static final PlayingCard COLOR = PlayingCard.joker(Joker.COLOR);

    @Test
    void the_deck_has_54_distinct_cards() {
        assertThat(Deck.all()).hasSize(Deck.SIZE).doesNotHaveDuplicates();
        assertThat(Deck.all()).filteredOn(PlayingCard::isJoker).containsExactly(BLACK, COLOR);
    }

    @Test
    void attack_values_follow_section_1() {
        assertThat(PlayingCard.of(Suit.SPADE, 2).attackValue()).isEqualTo(2);
        assertThat(PlayingCard.of(Suit.SPADE, PlayingCard.ACE).attackValue()).isEqualTo(3);
        assertThat(BLACK.attackValue()).isEqualTo(5);
        assertThat(COLOR.attackValue()).isEqualTo(7);
        assertThat(PlayingCard.of(Suit.SPADE, 5).attackValue()).isZero();
        assertThat(PlayingCard.of(Suit.SPADE, 5).isAttack()).isFalse();
    }

    @Test
    void attack_strength_orders_two_ace_black_color() {
        assertThat(PlayingCard.of(Suit.HEART, 2).attackStrength()).isEqualTo(1);
        assertThat(PlayingCard.of(Suit.HEART, PlayingCard.ACE).attackStrength()).isEqualTo(2);
        assertThat(BLACK.attackStrength()).isEqualTo(3);
        assertThat(COLOR.attackStrength()).isEqualTo(4);
        assertThat(PlayingCard.of(Suit.HEART, PlayingCard.KING).attackStrength()).isZero();
    }

    @Test
    void special_roles_belong_to_j_q_k_and_7_only() {
        assertThat(PlayingCard.of(Suit.CLUB, PlayingCard.JACK).isSkip()).isTrue();
        assertThat(PlayingCard.of(Suit.CLUB, PlayingCard.QUEEN).isReverse()).isTrue();
        assertThat(PlayingCard.of(Suit.CLUB, PlayingCard.KING).isExtraTurn()).isTrue();
        assertThat(PlayingCard.of(Suit.CLUB, 7).isSuitChange()).isTrue();
        assertThat(BLACK.isSkip() || BLACK.isReverse() || BLACK.isExtraTurn() || BLACK.isSuitChange()).isFalse();
    }

    @Test
    void normal_cards_are_3_to_10_without_7() {
        assertThat(Deck.all()).filteredOn(PlayingCard::isNormal).hasSize(28);
        assertThat(PlayingCard.of(Suit.DIAMOND, 7).isNormal()).isFalse();
        assertThat(PlayingCard.of(Suit.DIAMOND, 2).isNormal()).isFalse();
        assertThat(BLACK.isNormal()).isFalse();
    }

    @Test
    void malformed_cards_are_rejected() {
        assertThatThrownBy(() -> PlayingCard.of(Suit.SPADE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlayingCard.of(Suit.SPADE, 14)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlayingCard(Suit.SPADE, 0, Joker.BLACK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: FAIL — `compileTestJava` 에서 `cannot find symbol`(`PlayingCard`·`Deck`·`Joker`·`Suit` 가 아직 없다).

- [ ] **Step 4: 구현**

`server/src/main/java/com/mirboard/domain/game/onecard/card/Suit.java`:

```java
package com.mirboard.domain.game.onecard.card;

/** 무늬 4종 (`docs/rules-onecard.md` §1). 조커에는 무늬가 없다. */
public enum Suit {
    SPADE,
    HEART,
    DIAMOND,
    CLUB
}
```

`server/src/main/java/com/mirboard/domain/game/onecard/card/Joker.java`:

```java
package com.mirboard.domain.game.onecard.card;

/** 조커 2종 (`docs/rules-onecard.md` §1). 반격 세기는 흑백 < 컬러다 (§6.2). */
public enum Joker {
    BLACK,
    COLOR
}
```

`server/src/main/java/com/mirboard/domain/game/onecard/card/PlayingCard.java`:

```java
package com.mirboard.domain.game.onecard.card;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

/**
 * 원카드 카드 한 장 (`docs/rules-onecard.md` §1). 무늬 카드는 {@code suit}·{@code rank} 를, 조커는
 * {@code joker} 만 채운다 — 스컬킹 {@code SkullCard} 와 같은 단일 레코드라 JSON 으로 그대로 오간다.
 *
 * <p>{@code isGetterVisibility=NONE}: {@code isJoker()} 같은 판정 메서드가 직렬화 프로퍼티로 잡히면
 * 컴포넌트 {@code joker} 와 섞인다(D-102 의 스컬킹 사례). 판정 메서드에 {@code @JsonIgnore} 를 붙이면
 * 같은 이름의 컴포넌트까지 사라지므로 클래스 단위로 끈다.
 *
 * @param suit  무늬 카드의 무늬. 조커면 null
 * @param rank  A=1, 2~10, J=11, Q=12, K=13. 조커면 0
 * @param joker 조커 종류. 무늬 카드면 null
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
public record PlayingCard(Suit suit, int rank, Joker joker) {

    public static final int ACE = 1;
    public static final int JACK = 11;
    public static final int QUEEN = 12;
    public static final int KING = 13;

    public PlayingCard {
        if (joker == null) {
            if (suit == null || rank < ACE || rank > KING) {
                throw new IllegalArgumentException("invalid suit card: " + suit + " " + rank);
            }
        } else if (suit != null || rank != 0) {
            throw new IllegalArgumentException("a joker carries no suit or rank: " + joker);
        }
    }

    public static PlayingCard of(Suit suit, int rank) {
        return new PlayingCard(suit, rank, null);
    }

    public static PlayingCard joker(Joker joker) {
        return new PlayingCard(null, 0, joker);
    }

    public boolean isJoker() {
        return joker != null;
    }

    /** 공격 카드 — 2·A·조커 (§1). */
    public boolean isAttack() {
        return attackValue() > 0;
    }

    /** 먹일 장수 — 2 는 2, A 는 3, 흑백 조커 5, 컬러 조커 7, 그 밖은 0 (§1). */
    public int attackValue() {
        if (joker == Joker.BLACK) {
            return 5;
        }
        if (joker == Joker.COLOR) {
            return 7;
        }
        if (rank == 2) {
            return 2;
        }
        return rank == ACE ? 3 : 0;
    }

    /** 반격 세기 — 2 &lt; A &lt; 흑백 조커 &lt; 컬러 조커 (§6.2). 공격 카드가 아니면 0. */
    public int attackStrength() {
        if (joker == Joker.COLOR) {
            return 4;
        }
        if (joker == Joker.BLACK) {
            return 3;
        }
        if (rank == ACE) {
            return 2;
        }
        return rank == 2 ? 1 : 0;
    }

    /** J — 다음 사람 건너뛰기 (§8.1). */
    public boolean isSkip() {
        return joker == null && rank == JACK;
    }

    /** Q — 방향 반전 (§8.1). */
    public boolean isReverse() {
        return joker == null && rank == QUEEN;
    }

    /** K — 한 번 더 (§8.1). */
    public boolean isExtraTurn() {
        return joker == null && rank == KING;
    }

    /** 7 — 무늬 지정 (§8.1). */
    public boolean isSuitChange() {
        return joker == null && rank == 7;
    }

    /** 일반 카드 — 효과 없는 3·4·5·6·8·9·10 (§1). 시작 카드 조건이다 (§3-4). */
    public boolean isNormal() {
        return joker == null && rank >= 3 && rank <= 10 && rank != 7;
    }
}
```

`server/src/main/java/com/mirboard/domain/game/onecard/card/Deck.java`:

```java
package com.mirboard.domain.game.onecard.card;

import java.util.ArrayList;
import java.util.List;

/** 54장 덱 — 무늬 52 + 조커 2 (`docs/rules-onecard.md` §1). 섞기는 {@code Dealer} 가 한다. */
public final class Deck {

    public static final int SIZE = 54;

    private static final List<PlayingCard> ALL = build();

    private Deck() {
    }

    /** 54장 전부 — 무늬 순(♠♥♦♣), 무늬 안은 A→K, 마지막에 흑백·컬러 조커. */
    public static List<PlayingCard> all() {
        return ALL;
    }

    private static List<PlayingCard> build() {
        List<PlayingCard> cards = new ArrayList<>(SIZE);
        for (Suit suit : Suit.values()) {
            for (int rank = PlayingCard.ACE; rank <= PlayingCard.KING; rank++) {
                cards.add(PlayingCard.of(suit, rank));
            }
        }
        cards.add(PlayingCard.joker(Joker.BLACK));
        cards.add(PlayingCard.joker(Joker.COLOR));
        return List.copyOf(cards);
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: PASS — 원카드 테스트 6건 통과.

- [ ] **Step 6: 커밋**

```bash
git add docs/decisions.md \
  server/src/main/java/com/mirboard/domain/game/onecard/card \
  server/src/test/java/com/mirboard/domain/game/onecard/card
git commit -m "feat(D-127): 원카드 카드 모델 — 54장 덱·역할·공격 세기

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 2: 분배와 시작 카드 (`Dealer`)

**Files:**
- Create: `server/src/main/java/com/mirboard/domain/game/onecard/Dealer.java`
- Create: `server/src/test/java/com/mirboard/domain/game/onecard/DealerTest.java`

**Interfaces:**
- Consumes: Task 1 의 `PlayingCard`·`Deck`.
- Produces: `final class Dealer` — 상수 `HAND_SIZE=7`·`MIN_SEATS=2`·`MAX_SEATS=6`,
  `@FunctionalInterface interface Shuffler { List<PlayingCard> shuffle(List<PlayingCard> cards); }`,
  `record Deal(List<List<PlayingCard>> hands, List<PlayingCard> drawPile, PlayingCard startCard)`(뽑을 더미 0번이 맨 위),
  `static Shuffler random(Random rng)`, `static Deal deal(int seatCount, Shuffler shuffler)`.

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/DealerTest.java`:

```java
package com.mirboard.domain.game.onecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** `docs/rules-onecard.md` §3 — 분배와 시작 카드. */
class DealerTest {

    private static final PlayingCard SPADE_2 = PlayingCard.of(Suit.SPADE, 2);
    private static final PlayingCard HEART_5 = PlayingCard.of(Suit.HEART, 5);

    @ParameterizedTest(name = "{0}인")
    @ValueSource(ints = {2, 3, 4, 5, 6})
    void deals_seven_each_and_flips_a_normal_start_card(int seats) {
        Dealer.Deal deal = Dealer.deal(seats, Dealer.random(new Random(seats)));

        assertThat(deal.hands()).hasSize(seats).allSatisfy(hand -> assertThat(hand).hasSize(Dealer.HAND_SIZE));
        assertThat(deal.startCard().isNormal()).isTrue();
        List<PlayingCard> all = new ArrayList<>(deal.drawPile());
        deal.hands().forEach(all::addAll);
        all.add(deal.startCard());
        assertThat(all).hasSize(Deck.SIZE).doesNotHaveDuplicates();
    }

    @Test
    void a_non_normal_top_goes_to_the_bottom_and_the_next_card_is_flipped() {
        List<PlayingCard> order = new ArrayList<>(Deck.all());
        order.remove(SPADE_2);
        order.remove(HEART_5);
        order.add(14, SPADE_2);
        order.add(15, HEART_5);

        Dealer.Deal deal = Dealer.deal(2, cards -> order);

        assertThat(deal.startCard()).isEqualTo(HEART_5);
        assertThat(deal.drawPile()).hasSize(Deck.SIZE - 14 - 1).endsWith(SPADE_2);
    }

    @Test
    void when_the_pile_has_no_normal_card_everything_is_redealt() {
        List<PlayingCard> normal = Deck.all().stream().filter(PlayingCard::isNormal).toList();
        List<PlayingCard> other = Deck.all().stream().filter(card -> !card.isNormal()).toList();
        List<PlayingCard> bad = new ArrayList<>(normal);   // 28장 일반 카드가 전부 6인 손패(42장)로
        bad.addAll(other);
        AtomicInteger calls = new AtomicInteger();

        Dealer.Deal deal = Dealer.deal(6, cards -> calls.incrementAndGet() == 1 ? bad : Deck.all());

        assertThat(calls).hasValue(2);
        assertThat(deal.startCard().isNormal()).isTrue();
    }

    @Test
    void seat_counts_outside_2_to_6_are_rejected() {
        assertThatThrownBy(() -> Dealer.deal(1, Dealer.random(new Random(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dealer.deal(7, Dealer.random(new Random(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: FAIL — `compileTestJava` 에서 `cannot find symbol`(`Dealer` 가 아직 없다).

- [ ] **Step 3: 구현**

`server/src/main/java/com/mirboard/domain/game/onecard/Dealer.java`:

```java
package com.mirboard.domain.game.onecard;

import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 분배와 시작 카드 (`docs/rules-onecard.md` §3).
 *
 * <p>섞기는 {@link Shuffler} 로 주입한다 — 운영은 난수 셔플이고, 테스트는 정해 둔 순서를 준다. §3-5 를
 * 확인하려면 첫 분배는 일반 카드를 모두 손패로 보내고 다시 나눌 때 다른 순서를 주는 셔플러가 필요하다(§14).
 */
public final class Dealer {

    public static final int HAND_SIZE = 7;
    public static final int MIN_SEATS = 2;
    public static final int MAX_SEATS = 6;

    /** 54장을 섞은 새 목록을 돌려준다. 입력은 바꾸지 않는다. */
    @FunctionalInterface
    public interface Shuffler {
        List<PlayingCard> shuffle(List<PlayingCard> cards);
    }

    /**
     * 분배 결과.
     *
     * @param drawPile 0번이 맨 위
     */
    public record Deal(List<List<PlayingCard>> hands, List<PlayingCard> drawPile, PlayingCard startCard) {
        public Deal {
            hands = hands.stream().<List<PlayingCard>>map(List::copyOf).toList();
            drawPile = List.copyOf(drawPile);
        }
    }

    private Dealer() {
    }

    public static Shuffler random(Random rng) {
        return cards -> {
            List<PlayingCard> copy = new ArrayList<>(cards);
            Collections.shuffle(copy, rng);
            return copy;
        };
    }

    /**
     * §3 — 섞어서 7장씩 나누고 시작 카드를 뒤집는다. 일반 카드가 아니면 뽑을 더미 맨 아래로 보내고 다음을
     * 뒤집으며(§3-4), 더미를 한 바퀴 돌아도 없으면 54장을 다시 섞어 처음부터 한다(§3-5).
     */
    public static Deal deal(int seatCount, Shuffler shuffler) {
        if (seatCount < MIN_SEATS || seatCount > MAX_SEATS) {
            throw new IllegalArgumentException("One Card needs 2-6 seats: " + seatCount);
        }
        while (true) {
            List<PlayingCard> deck = shuffler.shuffle(Deck.all());
            List<List<PlayingCard>> hands = new ArrayList<>();
            for (int seat = 0; seat < seatCount; seat++) {
                hands.add(deck.subList(seat * HAND_SIZE, (seat + 1) * HAND_SIZE));
            }
            ArrayDeque<PlayingCard> pile = new ArrayDeque<>(deck.subList(seatCount * HAND_SIZE, deck.size()));
            for (int flips = pile.size(); flips > 0; flips--) {
                PlayingCard top = pile.pollFirst();
                if (top.isNormal()) {
                    return new Deal(hands, List.copyOf(pile), top);
                }
                pile.addLast(top);
            }
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: PASS — 원카드 테스트 누적 14건(DealerTest 8건).

- [ ] **Step 5: 커밋**

```bash
git add server/src/main/java/com/mirboard/domain/game/onecard/Dealer.java \
  server/src/test/java/com/mirboard/domain/game/onecard/DealerTest.java
git commit -m "feat(D-127): 원카드 분배 — 시작 카드 다시 뒤집기·처음부터 다시 나누기

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 3: 상태·액션·이벤트 타입 + JSON 왕복

**Files:**
- Create: `server/src/main/java/com/mirboard/domain/game/onecard/state/Elimination.java`, `state/MatchResult.java`, `state/RaceWindow.java`, `state/OneCardState.java`
- Create: `server/src/main/java/com/mirboard/domain/game/onecard/action/OneCardAction.java`, `action/RejectionReason.java`, `action/OneCardActionRejectedException.java`
- Create: `server/src/main/java/com/mirboard/domain/game/onecard/event/OneCardEvent.java`
- Create: `server/src/test/java/com/mirboard/domain/game/onecard/OneCardTables.java`(테스트용 상태 빌더), `server/src/test/java/com/mirboard/domain/game/onecard/OneCardJsonRoundTripTest.java`

**Interfaces:**
- Consumes: Task 1 카드 모델.
- Produces(이후 태스크가 그대로 쓴다):
  - `record Elimination(int seat, Reason reason, int cardsHeld)`, `enum Reason { BANKRUPT, DESERTED }`.
  - `record MatchResult(EndReason reason, List<Standing> standings)` — `enum EndReason { FINISHED, LAST_STANDING,
    NO_HUMANS, STALEMATE }`, `enum SeatStatus { FINISHED, ALIVE, BANKRUPT, DESERTED }`,
    `record Standing(int seat, int rank, int cardsLeft, SeatStatus status)`, `List<Integer> winners()`.
  - `record RaceWindow(int raceId, int ownerSeat, int slot, int jitterX, int jitterY, long openedAt, long windowMillis,
    int nextSeat, BotPress botPress)`, `record BotPress(int seat, boolean call, long delayMillis)`, `long deadline()`.
  - `record OneCardState(List<List<PlayingCard>> hands, List<PlayingCard> drawPile, List<PlayingCard> discardPile,
    int turnSeat, int direction, Suit declaredSuit, int attackStack, RaceWindow race, List<Elimination> eliminations,
    int passStreak, int turnCount, int version, MatchResult result) implements GameState` — `seatCount()`·`topCard()`·
    `baseSuit()`·`alive(int)`·`aliveSeats()`·`ended()`·`phaseName()`.
  - `sealed interface OneCardAction` — `PlayCard(PlayingCard card, Suit declaredSuit)`(`of(card)`), `Draw()`,
    `CallOneCard(int raceId)`, `Catch(int raceId)`.
  - `enum RejectionReason` 11개, `OneCardActionRejectedException(RejectionReason)` + `reason()`.
  - `sealed interface OneCardEvent` 11종 + `enum DrawReason { TURN, ATTACK, PENALTY }`, `enum RaceOutcome { CALLED,
    CAUGHT, EXPIRED, CANCELLED }`. 비공개는 `HandDealt`·`HandUpdated` 둘뿐(`privateSeat()`).
  - 테스트 빌더 `OneCardTables`: `seats(...)`, `hand(...)`, `spade/heart/diamond/club(rank)`, `BLACK_JOKER`·`COLOR_JOKER`,
    `top`·`drawPile`·`turn`·`direction`·`declared`·`attack`·`race`·`eliminated`·`passStreak`·`turnCount`·`build()` —
    남는 카드는 버린 더미 맨 위 아래에 넣어 54장 보존을 지킨다.

- [ ] **Step 1: 실패하는 테스트 작성** — 테스트 빌더와 JSON 왕복 테스트

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardTables.java`:

```java
package com.mirboard.domain.game.onecard;

import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.Joker;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.ArrayList;
import java.util.List;

/**
 * 테스트용 상태 빌더. 손패·맨 위·뽑을 더미만 정하면 나머지 카드는 버린 더미의 맨 위 아래에 넣어 54장 보존을
 * 지킨다 — 그래서 만든 상태가 그대로 불변식 검사를 통과한다.
 */
final class OneCardTables {

    static final PlayingCard BLACK_JOKER = PlayingCard.joker(Joker.BLACK);
    static final PlayingCard COLOR_JOKER = PlayingCard.joker(Joker.COLOR);

    private final List<List<PlayingCard>> hands;
    private PlayingCard top = heart(5);
    private List<PlayingCard> drawPile;
    private int turnSeat;
    private int direction = 1;
    private Suit declaredSuit;
    private int attackStack;
    private RaceWindow race;
    private final List<Elimination> eliminations = new ArrayList<>();
    private int passStreak;
    private int turnCount;

    private OneCardTables(List<List<PlayingCard>> hands) {
        this.hands = hands;
    }

    @SafeVarargs
    static OneCardTables seats(List<PlayingCard>... hands) {
        List<List<PlayingCard>> list = new ArrayList<>();
        for (List<PlayingCard> hand : hands) {
            list.add(hand);
        }
        return new OneCardTables(list);
    }

    static List<PlayingCard> hand(PlayingCard... cards) {
        return List.of(cards);
    }

    static PlayingCard spade(int rank) {
        return PlayingCard.of(Suit.SPADE, rank);
    }

    static PlayingCard heart(int rank) {
        return PlayingCard.of(Suit.HEART, rank);
    }

    static PlayingCard diamond(int rank) {
        return PlayingCard.of(Suit.DIAMOND, rank);
    }

    static PlayingCard club(int rank) {
        return PlayingCard.of(Suit.CLUB, rank);
    }

    OneCardTables top(PlayingCard card) {
        this.top = card;
        return this;
    }

    /** 뽑을 더미를 정확히 이 카드들로(0번이 맨 위). 정하지 않으면 남은 카드 전부. */
    OneCardTables drawPile(PlayingCard... cards) {
        this.drawPile = List.of(cards);
        return this;
    }

    OneCardTables turn(int seat) {
        this.turnSeat = seat;
        return this;
    }

    OneCardTables direction(int direction) {
        this.direction = direction;
        return this;
    }

    OneCardTables declared(Suit suit) {
        this.declaredSuit = suit;
        return this;
    }

    OneCardTables attack(int stack) {
        this.attackStack = stack;
        return this;
    }

    OneCardTables race(RaceWindow race) {
        this.race = race;
        this.turnSeat = -1;
        return this;
    }

    /** 그 좌석의 손패는 빈 목록이어야 한다(탈락자 손패는 더미로 갔다). */
    OneCardTables eliminated(int seat, Elimination.Reason reason, int cardsHeld) {
        this.eliminations.add(new Elimination(seat, reason, cardsHeld));
        return this;
    }

    OneCardTables passStreak(int streak) {
        this.passStreak = streak;
        return this;
    }

    OneCardTables turnCount(int count) {
        this.turnCount = count;
        return this;
    }

    OneCardState build() {
        List<PlayingCard> used = new ArrayList<>();
        hands.forEach(used::addAll);
        used.add(top);
        if (drawPile != null) {
            used.addAll(drawPile);
        }
        List<PlayingCard> rest = Deck.all().stream().filter(card -> !used.contains(card)).toList();
        List<PlayingCard> pile = drawPile != null ? drawPile : rest;
        List<PlayingCard> discard = new ArrayList<>(drawPile != null ? rest : List.of());
        discard.add(top);
        return new OneCardState(hands, pile, discard, turnSeat, direction, declaredSuit, attackStack, race,
                eliminations, passStreak, turnCount, 1, null);
    }
}
```

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardJsonRoundTripTest.java`:

```java
package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.BLACK_JOKER;
import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S3 가 상태를 Redis 에 JSON 으로 저장하고 액션을 클라 JSON 에서 읽는다. 판정 메서드({@code isJoker} 등)가
 * 프로퍼티로 새거나 컴포넌트를 지우는 회귀(D-102 의 스컬킹 사례)를 여기서 막는다.
 */
class OneCardJsonRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private <T> T roundTrip(T value, Class<T> type) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(value), type);
    }

    @Test
    void cards_round_trip() throws Exception {
        for (PlayingCard card : List.of(heart(5), BLACK_JOKER)) {
            assertThat(roundTrip(card, PlayingCard.class)).isEqualTo(card);
        }
    }

    @Test
    void a_state_with_a_race_a_declared_suit_and_an_elimination_round_trips() throws Exception {
        OneCardState state = seats(hand(club(3)), hand(), hand(diamond(4), diamond(6), spade(8)))
                .eliminated(1, Elimination.Reason.BANKRUPT, 20)
                .top(heart(7)).declared(Suit.SPADE).attack(0)
                .race(new RaceWindow(4, 0, 3, -20, 40, 1_000, 3_000, 2, new RaceWindow.BotPress(2, false, 1_500)))
                .build();

        assertThat(roundTrip(state, OneCardState.class)).isEqualTo(state);
    }

    @Test
    void a_finished_state_round_trips() throws Exception {
        OneCardState open = seats(hand(), hand(spade(4), spade(6))).top(heart(2)).turn(-1).build();
        OneCardState finished = new OneCardState(open.hands(), open.drawPile(), open.discardPile(), -1, 1, null, 0,
                null, List.of(), 0, 12, 9, new MatchResult(MatchResult.EndReason.FINISHED, List.of(
                        new MatchResult.Standing(0, 1, 0, MatchResult.SeatStatus.FINISHED),
                        new MatchResult.Standing(1, 2, 2, MatchResult.SeatStatus.ALIVE))));

        assertThat(roundTrip(finished, OneCardState.class)).isEqualTo(finished);
    }

    @Test
    void every_action_round_trips_through_the_sealed_type() throws Exception {
        for (OneCardAction action : List.of(new OneCardAction.PlayCard(heart(7), Suit.SPADE),
                OneCardAction.PlayCard.of(BLACK_JOKER), new OneCardAction.Draw(),
                new OneCardAction.CallOneCard(4), new OneCardAction.Catch(4))) {
            assertThat(roundTrip(action, OneCardAction.class)).isEqualTo(action);
        }
        assertThat(mapper.readValue("{\"@action\":\"DRAW\"}", OneCardAction.class))
                .isEqualTo(new OneCardAction.Draw());
    }

    @Test
    void events_carry_their_envelope_type_and_the_race_event_no_bot_timing() throws Exception {
        String json = mapper.writeValueAsString(new OneCardEvent.RaceOpened(7, 0, 3, -20, 40, 3_000));

        assertThat(json).contains("\"@event\":\"RACE_OPENED\"").doesNotContain("botPress").doesNotContain("delay");
        assertThat(new OneCardEvent.HandUpdated(2, List.of(), List.of(), 3).privateSeat()).isEqualTo(2);
        assertThat(new OneCardEvent.TurnChanged(2, 1, 0).privateSeat()).isEqualTo(-1);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: FAIL — `compileTestJava` 에서 `package com.mirboard.domain.game.onecard.state does not exist`·`cannot find symbol`(상태·액션·이벤트 타입이 아직 없다).

- [ ] **Step 3: 구현 — 상태**

`server/src/main/java/com/mirboard/domain/game/onecard/state/Elimination.java`:

```java
package com.mirboard.domain.game.onecard.state;

/**
 * 탈락 한 건 (`docs/rules-onecard.md` §10). 상태에는 일어난 순서대로 쌓인다 — 파산자 순위가 이 순서를
 * 읽는다(늦게 파산한 쪽이 위, §11.2).
 *
 * @param cardsHeld 탈락 순간 손에 있던 장수. 손패는 뽑을 더미로 가므로 순위표용으로 따로 남긴다
 */
public record Elimination(int seat, Reason reason, int cardsHeld) {

    public enum Reason {
        /** 먹은 뒤 손패 20장 이상. */
        BANKRUPT,
        /** 게임 중 나가기·끊김 유예 초과. */
        DESERTED
    }
}
```

`server/src/main/java/com/mirboard/domain/game/onecard/state/MatchResult.java`:

```java
package com.mirboard.domain.game.onecard.state;

import java.util.List;

/**
 * 끝난 매치의 결과 (`docs/rules-onecard.md` §11). 1판 = 1매치라 라운드 누적이 없다.
 *
 * @param standings 좌석마다 한 줄, 순위 오름차순(같은 순위는 좌석 오름차순)
 */
public record MatchResult(EndReason reason, List<Standing> standings) {

    public MatchResult {
        standings = List.copyOf(standings);
    }

    /** 종료 사유 — §11.1 의 판정 순서와 같다. */
    public enum EndReason {
        FINISHED,
        LAST_STANDING,
        NO_HUMANS,
        STALEMATE
    }

    public enum SeatStatus {
        /** 마지막 카드를 냈다. */
        FINISHED,
        /** 끝까지 살아 있었다. */
        ALIVE,
        BANKRUPT,
        DESERTED
    }

    /**
     * @param rank      1부터. 동순위 다음은 건너뛴다(1, 1, 3)
     * @param cardsLeft 살아 있으면 남은 장수, 탈락했으면 탈락 순간의 장수
     */
    public record Standing(int seat, int rank, int cardsLeft, SeatStatus status) {
    }

    /** 승자 — 1등 전원(동순위 포함, §11.2). */
    public List<Integer> winners() {
        return standings.stream().filter(s -> s.rank() == 1).map(Standing::seat).toList();
    }
}
```

`server/src/main/java/com/mirboard/domain/game/onecard/state/RaceWindow.java`:

```java
package com.mirboard.domain.game.onecard.state;

/**
 * 열린 외치기 경쟁 창 (`docs/rules-onecard.md` §9).
 *
 * @param raceId       창 번호 — 이 번호가 다른 누름은 거절한다(§9-5)
 * @param ownerSeat    1장 남은 사람
 * @param slot         버튼 위치 슬롯(0..slotCount-1). 전원에게 같은 위치다
 * @param jitterX      슬롯 안 가로 흔들림, −100~100 (클라가 슬롯 반경으로 환산)
 * @param jitterY      세로 흔들림, −100~100
 * @param openedAt     창을 연 시각(epoch ms)
 * @param windowMillis 창 길이
 * @param nextSeat     창이 닫히면 차례를 받을 좌석 — 카드를 낸 순간 정해 둔다(§9.2)
 * @param botPress     창을 열 때 추첨한 가장 빠른 봇의 누름. 봇이 없거나 창보다 늦으면 null.
 *                     <b>서버 전용</b> — 공개 뷰·이벤트에 싣지 않는다(설계서 §4.9)
 */
public record RaceWindow(int raceId,
                         int ownerSeat,
                         int slot,
                         int jitterX,
                         int jitterY,
                         long openedAt,
                         long windowMillis,
                         int nextSeat,
                         BotPress botPress) {

    /**
     * 봇 한 명의 누름.
     *
     * @param call        true 면 주인의 "원카드!", false 면 다른 봇의 "잡기!"
     * @param delayMillis 창이 열린 뒤 누르기까지의 반응 시간
     */
    public record BotPress(int seat, boolean call, long delayMillis) {
    }

    /** 창이 저절로 닫히는 시각 — 가장 빠른 봇의 누름 또는 창 길이 중 이른 쪽. */
    public long deadline() {
        return openedAt + (botPress != null ? botPress.delayMillis() : windowMillis);
    }
}
```

`server/src/main/java/com/mirboard/domain/game/onecard/state/OneCardState.java`:

```java
package com.mirboard.domain.game.onecard.state;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 원카드 한 판의 전체 상태 (`docs/rules-onecard.md`). 1판 = 1매치라 라운드·매치 상태를 나누지 않는다.
 *
 * <p>단계(진행·경쟁·종료)를 sealed 하위 타입으로 나누지 않은 것은 의도적이다 — 스컬킹은 단계마다 들고
 * 있는 것이 달랐지만, 원카드는 세 단계가 같은 테이블(손패·더미·차례)을 공유하고 경쟁 창과 결과만
 * 붙었다 떨어진다. 그래서 {@code race}·{@code result} 를 nullable 로 두고 단계는 파생한다.
 *
 * @param hands        좌석별 손패. 탈락자는 빈 목록
 * @param drawPile     뽑을 더미, 0번이 맨 위
 * @param discardPile  버린 더미, 마지막이 맨 위
 * @param turnSeat     차례인 좌석. 경쟁 창이 열렸거나 끝났으면 −1
 * @param direction    +1 이면 좌석 번호가 커지는 쪽
 * @param declaredSuit 맨 위가 7 일 때 지정된 무늬, 아니면 null
 * @param attackStack  누적 공격 장수. 0 이면 공격 없음
 * @param race         열린 경쟁 창, 없으면 null
 * @param eliminations 탈락 순서
 * @param passStreak   연속 패스 수(§11.3)
 * @param turnCount    내기·먹기 횟수(§11.3 차례 상한)
 * @param version      전이마다 1씩 오른다 — 비공개 손패 이벤트의 {@code handVersion}
 * @param result       끝났으면 결과, 아니면 null
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
public record OneCardState(List<List<PlayingCard>> hands,
                           List<PlayingCard> drawPile,
                           List<PlayingCard> discardPile,
                           int turnSeat,
                           int direction,
                           Suit declaredSuit,
                           int attackStack,
                           RaceWindow race,
                           List<Elimination> eliminations,
                           int passStreak,
                           int turnCount,
                           int version,
                           MatchResult result) implements GameState {

    public OneCardState {
        hands = hands.stream().<List<PlayingCard>>map(List::copyOf).toList();
        drawPile = List.copyOf(drawPile);
        discardPile = List.copyOf(discardPile);
        eliminations = eliminations == null ? List.of() : List.copyOf(eliminations);
    }

    public int seatCount() {
        return hands.size();
    }

    public PlayingCard topCard() {
        return discardPile.get(discardPile.size() - 1);
    }

    /** 기준 무늬(§5.1) — 7 로 지정된 무늬, 없으면 맨 위 카드의 무늬. 맨 위가 조커면 null. */
    public Suit baseSuit() {
        return declaredSuit != null ? declaredSuit : topCard().suit();
    }

    public boolean alive(int seat) {
        return eliminations.stream().noneMatch(e -> e.seat() == seat);
    }

    public List<Integer> aliveSeats() {
        return IntStream.range(0, seatCount()).filter(this::alive).boxed().toList();
    }

    public boolean ended() {
        return result != null;
    }

    /** 클라 분기용 단계 이름 — 포트의 {@code phaseName} 이 그대로 쓴다. */
    public String phaseName() {
        if (result != null) {
            return "ENDED";
        }
        return race != null ? "RACE" : "PLAYING";
    }
}
```

- [ ] **Step 4: 구현 — 액션·거절**

`server/src/main/java/com/mirboard/domain/game/onecard/action/OneCardAction.java`:

```java
package com.mirboard.domain.game.onecard.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;

/**
 * 원카드 액션 sealed 계층 — 클라가 보낼 수 있는 것 전부(설계서 §4.3).
 *
 * <p>"창 닫기" 같은 시스템 전이는 여기 없다. 클라 JSON 으로 역직렬화되는 계층에 넣으면 클라가 위조해 보낼
 * 수 있으므로, 시간에 따른 전이는 엔진의 {@code onTimer} 가 따로 맡는다(설계서 §4.5).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OneCardAction.PlayCard.class, name = "PLAY_CARD"),
        @JsonSubTypes.Type(value = OneCardAction.Draw.class, name = "DRAW"),
        @JsonSubTypes.Type(value = OneCardAction.CallOneCard.class, name = "CALL_ONE_CARD"),
        @JsonSubTypes.Type(value = OneCardAction.Catch.class, name = "CATCH")
})
public sealed interface OneCardAction extends GameAction
        permits OneCardAction.PlayCard, OneCardAction.Draw, OneCardAction.CallOneCard, OneCardAction.Catch {

    /**
     * 카드 한 장 내기 (§5).
     *
     * @param declaredSuit 7 을 낼 때만 채운다(§8.1). 다른 카드에 실으면 거절
     */
    record PlayCard(PlayingCard card, Suit declaredSuit) implements OneCardAction {

        public static PlayCard of(PlayingCard card) {
            return new PlayCard(card, null);
        }
    }

    /** 먹기 (§7) — 공격받는 중이면 누적 장수, 아니면 1장. */
    record Draw() implements OneCardAction {
    }

    /** "원카드!" — 창 주인만 (§9). */
    record CallOneCard(int raceId) implements OneCardAction {
    }

    /** "잡기!" — 창 주인이 아닌 살아 있는 사람 (§9). */
    record Catch(int raceId) implements OneCardAction {
    }
}
```

`server/src/main/java/com/mirboard/domain/game/onecard/action/RejectionReason.java`:

```java
package com.mirboard.domain.game.onecard.action;

/**
 * 액션 거절 사유. {@code name()} 이 그대로 STOMP {@code ERROR} envelope 의 {@code code} 가 되므로 값 이름은
 * 클라와의 계약이다 — 바꾸면 클라도 함께 고쳐야 한다(스컬킹과 같은 규약).
 */
public enum RejectionReason {

    /** 매치가 이미 끝났다. */
    MATCH_OVER,

    /** 탈락(파산·탈주)한 좌석의 액션 (§10). */
    PLAYER_ELIMINATED,

    /** 경쟁 창이 열린 동안의 내기·먹기 (§9-2). */
    RACE_IN_PROGRESS,

    /** 본인 차례가 아니다. */
    NOT_YOUR_TURN,

    /** 손패에 없는 카드. */
    CARD_NOT_OWNED,

    /** 7 인데 무늬를 지정하지 않았거나, 7 이 아닌데 지정했다 (§8.1). */
    INVALID_SUIT_DECLARATION,

    /** 공격받는 중이 아닌데 맨 위와 맞지 않는 카드 (§5.2). */
    CARD_NOT_PLAYABLE,

    /** 공격받는 중인데 반격 조건을 지키는 공격 카드가 아니다 (§5.3, §6.2). */
    COUNTER_REQUIRED,

    /** 열린 경쟁 창이 없거나 창 번호가 다르다 (§9-5). */
    NO_RACE,

    /** "원카드!" 는 창 주인만 누른다. */
    NOT_RACE_OWNER,

    /** 창 주인은 "잡기!" 를 누를 수 없다. */
    OWNER_CANNOT_CATCH
}
```

`server/src/main/java/com/mirboard/domain/game/onecard/action/OneCardActionRejectedException.java`:

```java
package com.mirboard.domain.game.onecard.action;

import com.mirboard.domain.game.core.GameActionRejectedException;

/**
 * 포트 예외 {@link GameActionRejectedException} 의 원카드 구현. 인프라는 {@code code()}
 * (= {@code reason().name()}) 만 읽고 {@link RejectionReason} 은 도메인 안에 남는다.
 */
public final class OneCardActionRejectedException extends GameActionRejectedException {

    private final RejectionReason reason;

    public OneCardActionRejectedException(RejectionReason reason) {
        super(reason.name(), "One Card action rejected: " + reason);
        this.reason = reason;
    }

    public RejectionReason reason() {
        return reason;
    }
}
```

- [ ] **Step 5: 구현 — 이벤트**

`server/src/main/java/com/mirboard/domain/game/onecard/event/OneCardEvent.java`:

```java
package com.mirboard.domain.game.onecard.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult;
import java.util.List;

/**
 * 원카드 엔진이 발행하는 이벤트 sealed 계층 (설계서 §4.3).
 *
 * <p><b>payload 는 증감이 아니라 결과값을 싣는다</b> — 좌석 손패 장수, 공격 누적, 뽑을 더미 장수 등.
 * 잠금 없는 resync 와 겹쳐 같은 이벤트가 두 번 적용되거나 하나가 빠져도 다음 이벤트에서 맞춰진다.
 *
 * <p><b>State Hiding (D-01).</b> 손패 카드는 비공개 {@link HandDealt}·{@link HandUpdated} 에만 담긴다.
 * 이 둘은 손패 전체와 {@code handVersion} 을 실어, 클라가 더 낮은 버전을 버리면 순서가 뒤바뀌어도 손패가
 * 되돌아가지 않는다. 봇의 반응 시각(경쟁 창의 {@code botPress})은 어떤 이벤트에도 싣지 않는다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@event")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OneCardEvent.MatchStarted.class, name = "MATCH_STARTED"),
        @JsonSubTypes.Type(value = OneCardEvent.HandDealt.class, name = "HAND_DEALT"),
        @JsonSubTypes.Type(value = OneCardEvent.HandUpdated.class, name = "HAND_UPDATED"),
        @JsonSubTypes.Type(value = OneCardEvent.CardPlayed.class, name = "CARD_PLAYED"),
        @JsonSubTypes.Type(value = OneCardEvent.CardsDrawn.class, name = "CARDS_DRAWN"),
        @JsonSubTypes.Type(value = OneCardEvent.PileReshuffled.class, name = "PILE_RESHUFFLED"),
        @JsonSubTypes.Type(value = OneCardEvent.TurnChanged.class, name = "TURN_CHANGED"),
        @JsonSubTypes.Type(value = OneCardEvent.RaceOpened.class, name = "RACE_OPENED"),
        @JsonSubTypes.Type(value = OneCardEvent.RaceResolved.class, name = "RACE_RESOLVED"),
        @JsonSubTypes.Type(value = OneCardEvent.PlayerEliminated.class, name = "PLAYER_ELIMINATED"),
        @JsonSubTypes.Type(value = OneCardEvent.MatchEnded.class, name = "MATCH_ENDED")
})
public sealed interface OneCardEvent extends GameEvent
        permits OneCardEvent.MatchStarted,
                OneCardEvent.HandDealt,
                OneCardEvent.HandUpdated,
                OneCardEvent.CardPlayed,
                OneCardEvent.CardsDrawn,
                OneCardEvent.PileReshuffled,
                OneCardEvent.TurnChanged,
                OneCardEvent.RaceOpened,
                OneCardEvent.RaceResolved,
                OneCardEvent.PlayerEliminated,
                OneCardEvent.MatchEnded {

    @Override
    default String envelopeType() {
        return switch (this) {
            case MatchStarted __ -> "MATCH_STARTED";
            case HandDealt __ -> "HAND_DEALT";
            case HandUpdated __ -> "HAND_UPDATED";
            case CardPlayed __ -> "CARD_PLAYED";
            case CardsDrawn __ -> "CARDS_DRAWN";
            case PileReshuffled __ -> "PILE_RESHUFFLED";
            case TurnChanged __ -> "TURN_CHANGED";
            case RaceOpened __ -> "RACE_OPENED";
            case RaceResolved __ -> "RACE_RESOLVED";
            case PlayerEliminated __ -> "PLAYER_ELIMINATED";
            case MatchEnded __ -> "MATCH_ENDED";
        };
    }

    /** 손패를 담은 두 이벤트만 비공개다. */
    @Override
    default int privateSeat() {
        return switch (this) {
            case HandDealt dealt -> dealt.seat();
            case HandUpdated updated -> updated.seat();
            default -> -1;
        };
    }

    /** 먹은 이유 (§6.3, §7, §9.1). */
    enum DrawReason {
        TURN,
        ATTACK,
        PENALTY
    }

    /** 경쟁 결과 (§9-4). {@code CANCELLED} 는 창 중 탈주로 벌칙 없이 닫힌 것(§9-7). */
    enum RaceOutcome {
        CALLED,
        CAUGHT,
        EXPIRED,
        CANCELLED
    }

    /** 공개 — 분배 직후의 테이블. */
    record MatchStarted(int firstSeat, PlayingCard startCard, int handSize, int drawPileCount)
            implements OneCardEvent {
    }

    /** 비공개 — 처음 받은 손패. */
    record HandDealt(int seat, List<PlayingCard> hand, int handVersion) implements OneCardEvent {
        public HandDealt {
            hand = List.copyOf(hand);
        }
    }

    /**
     * 비공개 — 손패가 바뀔 때마다(내기·먹기·벌칙·탈락) 손패 전체를 다시 보낸다.
     *
     * @param received 이번에 새로 받은 카드(애니메이션용). 내기·탈락이면 빈 목록
     */
    record HandUpdated(int seat, List<PlayingCard> hand, List<PlayingCard> received, int handVersion)
            implements OneCardEvent {
        public HandUpdated {
            hand = List.copyOf(hand);
            received = List.copyOf(received);
        }
    }

    /**
     * 공개 — 카드 한 장을 냈다.
     *
     * @param handCount   낸 뒤 그 좌석의 손패 장수
     * @param attackStack 낸 뒤의 공격 누적
     */
    record CardPlayed(int seat, PlayingCard card, Suit declaredSuit, int handCount, int attackStack)
            implements OneCardEvent {
    }

    /**
     * 공개 — 카드를 먹었다(장수만).
     *
     * @param count         실제로 먹은 장수(더미가 모자라면 요구보다 적다)
     * @param handCount     먹은 뒤 그 좌석의 손패 장수
     * @param drawPileCount 먹은 뒤 뽑을 더미 장수
     */
    record CardsDrawn(int seat, int count, DrawReason reason, int handCount, int drawPileCount)
            implements OneCardEvent {
    }

    /** 공개 — 버린 더미를 섞어 뽑을 더미를 다시 채웠다(§7.1). */
    record PileReshuffled(int drawPileCount) implements OneCardEvent {
    }

    /** 공개 — 차례가 넘어갔다. {@code attackStack} 이 0 보다 크면 그 좌석이 공격받는 중이다. */
    record TurnChanged(int seat, int direction, int attackStack) implements OneCardEvent {
    }

    /** 공개 — 경쟁 창이 열렸다. 봇의 반응 시각은 싣지 않는다. */
    record RaceOpened(int raceId, int ownerSeat, int slot, int jitterX, int jitterY, long windowMillis)
            implements OneCardEvent {
    }

    /** 공개 — 경쟁 창이 닫혔다. {@code bySeat} 는 누른 좌석, 아무도 안 눌렀으면 −1. */
    record RaceResolved(int raceId, RaceOutcome outcome, int bySeat) implements OneCardEvent {
    }

    /** 공개 — 탈락. */
    record PlayerEliminated(int seat, Elimination.Reason reason, int cardsHeld) implements OneCardEvent {
    }

    /** 공개 — 매치 종료와 순위. */
    record MatchEnded(MatchResult.EndReason reason, List<MatchResult.Standing> standings)
            implements OneCardEvent {
        public MatchEnded {
            standings = List.copyOf(standings);
        }
    }
}
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: PASS — 원카드 테스트 누적 19건(JSON 왕복 5건). 출력에 원카드 파일 관련 경고가 없어야 한다.

- [ ] **Step 7: 커밋**

```bash
git add server/src/main/java/com/mirboard/domain/game/onecard/state \
  server/src/main/java/com/mirboard/domain/game/onecard/action \
  server/src/main/java/com/mirboard/domain/game/onecard/event \
  server/src/test/java/com/mirboard/domain/game/onecard/OneCardTables.java \
  server/src/test/java/com/mirboard/domain/game/onecard/OneCardJsonRoundTripTest.java
git commit -m "feat(D-127): 원카드 상태·액션·이벤트 타입 + JSON 왕복

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 4: 규칙 — 낼 수 있는 카드 · 다음 차례 · 순위

**Files:**
- Create: `server/src/main/java/com/mirboard/domain/game/onecard/rules/PlayRules.java`, `rules/TurnOrder.java`, `rules/Ranking.java`
- Create: `server/src/test/java/com/mirboard/domain/game/onecard/rules/PlayRulesTest.java`, `rules/TurnOrderTest.java`, `rules/RankingTest.java`

**Interfaces:**
- Consumes: Task 1 카드, Task 3 `Elimination`·`MatchResult`.
- Produces: `PlayRules.canPlay(PlayingCard top, Suit baseSuit, int attackStack, PlayingCard card)`(§5.2·§5.3·§6.2),
  `TurnOrder.nextAlive(int seatCount, IntPredicate alive, int from, int direction)`,
  `TurnOrder.afterPlay(int seatCount, IntPredicate alive, int seat, int direction, PlayingCard card)`(Q 는 이미 뒤집힌
  방향을 받는다), `Ranking.rank(List<List<PlayingCard>> hands, List<Elimination> eliminations, int finisher)`
  → 순위 오름차순 `List<Standing>`(finisher 없으면 −1).

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/rules/PlayRulesTest.java`:

```java
package com.mirboard.domain.game.onecard.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.onecard.card.Joker;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import org.junit.jupiter.api.Test;

/** `docs/rules-onecard.md` §5.2·§5.3·§6.2 — 지금 이 카드를 낼 수 있는가. */
class PlayRulesTest {

    private static final PlayingCard BLACK = PlayingCard.joker(Joker.BLACK);
    private static final PlayingCard COLOR = PlayingCard.joker(Joker.COLOR);

    private static PlayingCard card(Suit suit, int rank) {
        return PlayingCard.of(suit, rank);
    }

    /** 공격받는 중이 아님 — 기준 무늬는 맨 위 카드의 무늬. */
    private static boolean free(PlayingCard top, PlayingCard card) {
        return PlayRules.canPlay(top, top.suit(), 0, card);
    }

    /** 공격받는 중. */
    private static boolean underAttack(PlayingCard top, PlayingCard card) {
        return PlayRules.canPlay(top, top.suit(), top.attackValue(), card);
    }

    @Test
    void same_suit_or_same_rank_matches() {
        PlayingCard top = card(Suit.HEART, 5);
        assertThat(free(top, card(Suit.HEART, 9))).isTrue();
        assertThat(free(top, card(Suit.SPADE, 5))).isTrue();
        assertThat(free(top, card(Suit.SPADE, 9))).isFalse();
    }

    @Test
    void a_declared_suit_replaces_the_sevens_suit_but_a_seven_still_matches_a_seven() {
        PlayingCard top = card(Suit.HEART, 7);
        assertThat(PlayRules.canPlay(top, Suit.SPADE, 0, card(Suit.SPADE, 3))).isTrue();
        assertThat(PlayRules.canPlay(top, Suit.SPADE, 0, card(Suit.HEART, 3))).isFalse();
        assertThat(PlayRules.canPlay(top, Suit.SPADE, 0, card(Suit.CLUB, 7))).isTrue();
    }

    @Test
    void a_joker_can_always_be_played_when_not_under_attack() {
        assertThat(free(card(Suit.HEART, 5), BLACK)).isTrue();
        assertThat(free(card(Suit.HEART, 5), COLOR)).isTrue();
    }

    @Test
    void anything_goes_on_a_joker_once_the_attack_is_over() {
        assertThat(PlayRules.canPlay(BLACK, null, 0, card(Suit.CLUB, 9))).isTrue();
        assertThat(PlayRules.canPlay(COLOR, null, 0, card(Suit.DIAMOND, PlayingCard.KING))).isTrue();
    }

    @Test
    void a_seven_is_not_wild() {
        assertThat(free(card(Suit.HEART, 5), card(Suit.SPADE, 7))).isFalse();
    }

    @Test
    void under_a_two_any_two_the_base_suit_ace_or_a_joker_counters() {
        PlayingCard top = card(Suit.HEART, 2);
        assertThat(underAttack(top, card(Suit.SPADE, 2))).isTrue();
        assertThat(underAttack(top, card(Suit.HEART, PlayingCard.ACE))).isTrue();
        assertThat(underAttack(top, card(Suit.SPADE, PlayingCard.ACE))).isFalse();
        assertThat(underAttack(top, BLACK)).isTrue();
        assertThat(underAttack(top, COLOR)).isTrue();
    }

    @Test
    void under_an_ace_only_aces_and_jokers_counter() {
        PlayingCard top = card(Suit.HEART, PlayingCard.ACE);
        assertThat(underAttack(top, card(Suit.CLUB, PlayingCard.ACE))).isTrue();
        assertThat(underAttack(top, card(Suit.HEART, 2))).isFalse();
        assertThat(underAttack(top, BLACK)).isTrue();
        assertThat(underAttack(top, COLOR)).isTrue();
    }

    @Test
    void under_a_black_joker_only_the_color_joker_counters_and_nothing_beats_the_color_joker() {
        assertThat(PlayRules.canPlay(BLACK, null, 5, COLOR)).isTrue();
        assertThat(PlayRules.canPlay(BLACK, null, 5, card(Suit.HEART, PlayingCard.ACE))).isFalse();
        assertThat(PlayRules.canPlay(COLOR, null, 7, BLACK)).isFalse();
        assertThat(PlayRules.canPlay(COLOR, null, 7, card(Suit.HEART, 2))).isFalse();
    }

    @Test
    void under_attack_normal_and_special_cards_cannot_be_played() {
        PlayingCard top = card(Suit.HEART, 2);
        assertThat(underAttack(top, card(Suit.HEART, 5))).isFalse();
        assertThat(underAttack(top, card(Suit.HEART, 7))).isFalse();
        assertThat(underAttack(top, card(Suit.HEART, PlayingCard.KING))).isFalse();
    }
}
```

`server/src/test/java/com/mirboard/domain/game/onecard/rules/TurnOrderTest.java`:

```java
package com.mirboard.domain.game.onecard.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/** `docs/rules-onecard.md` §8.2 — 다음 차례 표. */
class TurnOrderTest {

    private static final IntPredicate ALL = seat -> true;

    private static PlayingCard heart(int rank) {
        return PlayingCard.of(Suit.HEART, rank);
    }

    @Test
    void a_normal_card_or_a_seven_passes_to_the_next_seat_in_the_current_direction() {
        assertThat(TurnOrder.afterPlay(4, ALL, 1, +1, heart(5))).isEqualTo(2);
        assertThat(TurnOrder.afterPlay(4, ALL, 1, -1, heart(7))).isEqualTo(0);
    }

    @Test
    void an_attack_card_passes_to_the_next_seat() {
        assertThat(TurnOrder.afterPlay(4, ALL, 3, +1, heart(2))).isEqualTo(0);
    }

    @Test
    void a_jack_skips_one_seat() {
        assertThat(TurnOrder.afterPlay(4, ALL, 1, +1, heart(PlayingCard.JACK))).isEqualTo(3);
    }

    @Test
    void a_queen_goes_the_other_way() {
        // Q 는 엔진이 방향을 먼저 뒤집고 넘긴다.
        assertThat(TurnOrder.afterPlay(4, ALL, 1, -1, heart(PlayingCard.QUEEN))).isEqualTo(0);
    }

    @Test
    void a_king_gives_the_same_seat_another_turn() {
        assertThat(TurnOrder.afterPlay(4, ALL, 1, +1, heart(PlayingCard.KING))).isEqualTo(1);
    }

    @Test
    void with_two_live_seats_jack_and_queen_also_give_another_turn() {
        IntPredicate twoLeft = seat -> seat == 0 || seat == 2;
        assertThat(TurnOrder.afterPlay(4, twoLeft, 0, +1, heart(PlayingCard.JACK))).isEqualTo(0);
        assertThat(TurnOrder.afterPlay(4, twoLeft, 0, -1, heart(PlayingCard.QUEEN))).isEqualTo(0);
    }

    @Test
    void eliminated_seats_are_skipped() {
        IntPredicate withoutTwo = seat -> seat != 2;
        assertThat(TurnOrder.afterPlay(4, withoutTwo, 1, +1, heart(5))).isEqualTo(3);
        assertThat(TurnOrder.afterPlay(4, withoutTwo, 1, +1, heart(PlayingCard.JACK))).isEqualTo(0);
    }

    @Test
    void next_alive_wraps_around_both_ways() {
        assertThat(TurnOrder.nextAlive(4, ALL, 3, +1)).isEqualTo(0);
        assertThat(TurnOrder.nextAlive(4, ALL, 0, -1)).isEqualTo(3);
    }
}
```

`server/src/test/java/com/mirboard/domain/game/onecard/rules/RankingTest.java`:

```java
package com.mirboard.domain.game.onecard.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult.SeatStatus;
import com.mirboard.domain.game.onecard.state.MatchResult.Standing;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** `docs/rules-onecard.md` §11.2 — 순위. */
class RankingTest {

    /** 장수만 의미 있는 손패 — 순위는 장수만 본다. */
    private static List<PlayingCard> cards(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(rank -> PlayingCard.of(Suit.SPADE, rank)).toList();
    }

    @Test
    void the_finisher_is_first_and_the_rest_rank_by_cards_left_sharing_ties() {
        List<Standing> standings = Ranking.rank(
                List.of(cards(0), cards(3), cards(1), cards(3)), List.of(), 0);

        assertThat(standings).containsExactly(
                new Standing(0, 1, 0, SeatStatus.FINISHED),
                new Standing(2, 2, 1, SeatStatus.ALIVE),
                new Standing(1, 3, 3, SeatStatus.ALIVE),
                new Standing(3, 3, 3, SeatStatus.ALIVE));
    }

    @Test
    void without_a_finisher_tied_leaders_share_first_place_and_the_next_rank_is_skipped() {
        List<Standing> standings = Ranking.rank(List.of(cards(2), cards(2), cards(5)), List.of(), -1);

        assertThat(standings).extracting(Standing::rank).containsExactly(1, 1, 3);
    }

    @Test
    void bankrupt_seats_rank_below_the_living_and_the_later_bankruptcy_ranks_higher() {
        List<Standing> standings = Ranking.rank(
                List.of(cards(4), cards(0), cards(6), cards(0)),
                List.of(new Elimination(1, Elimination.Reason.BANKRUPT, 20),
                        new Elimination(3, Elimination.Reason.BANKRUPT, 21)),
                -1);

        assertThat(standings).containsExactly(
                new Standing(0, 1, 4, SeatStatus.ALIVE),
                new Standing(2, 2, 6, SeatStatus.ALIVE),
                new Standing(3, 3, 21, SeatStatus.BANKRUPT),
                new Standing(1, 4, 20, SeatStatus.BANKRUPT));
    }

    @Test
    void deserters_share_the_bottom_rank_below_bankrupt_seats() {
        List<Standing> standings = Ranking.rank(
                List.of(cards(3), cards(0), cards(0), cards(0)),
                List.of(new Elimination(2, Elimination.Reason.DESERTED, 5),
                        new Elimination(1, Elimination.Reason.BANKRUPT, 20),
                        new Elimination(3, Elimination.Reason.DESERTED, 9)),
                -1);

        assertThat(standings).containsExactly(
                new Standing(0, 1, 3, SeatStatus.ALIVE),
                new Standing(1, 2, 20, SeatStatus.BANKRUPT),
                new Standing(2, 3, 5, SeatStatus.DESERTED),
                new Standing(3, 3, 9, SeatStatus.DESERTED));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: FAIL — `compileTestJava` 에서 `cannot find symbol`(`PlayRules`·`TurnOrder`·`Ranking` 이 아직 없다).

- [ ] **Step 3: 구현**

`server/src/main/java/com/mirboard/domain/game/onecard/rules/PlayRules.java`:

```java
package com.mirboard.domain.game.onecard.rules;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;

/** 지금 이 카드를 낼 수 있는가 (`docs/rules-onecard.md` §5.2, §5.3, §6.2). */
public final class PlayRules {

    private PlayRules() {
    }

    /**
     * @param top         버린 더미 맨 위
     * @param baseSuit    기준 무늬(§5.1) — 맨 위가 조커면 null
     * @param attackStack 공격 누적. 0 보다 크면 공격받는 중이다
     */
    public static boolean canPlay(PlayingCard top, Suit baseSuit, int attackStack, PlayingCard card) {
        return attackStack > 0 ? canCounter(top, baseSuit, card) : matches(top, baseSuit, card);
    }

    /** §5.2 — 공격받는 중이 아닐 때. 7 도 같은 조건이다(와일드 아님). */
    static boolean matches(PlayingCard top, Suit baseSuit, PlayingCard card) {
        if (card.isJoker() || top.isJoker()) {
            return true;
        }
        return card.suit() == baseSuit || card.rank() == top.rank();
    }

    /**
     * §6.2 — 공격받는 중일 때. 공격 카드만, 맨 위 공격 이상의 세기만. 같은 숫자는 무늬와 무관하고, 다른 숫자는
     * 기준 무늬가 같아야 하며, 조커는 무늬와 무관하다. 조커 공격에는 조커만 남는다(세기 조건).
     */
    static boolean canCounter(PlayingCard top, Suit baseSuit, PlayingCard card) {
        if (!card.isAttack() || card.attackStrength() < top.attackStrength()) {
            return false;
        }
        if (card.isJoker()) {
            return true;
        }
        return card.rank() == top.rank() || card.suit() == baseSuit;
    }
}
```

`server/src/main/java/com/mirboard/domain/game/onecard/rules/TurnOrder.java`:

```java
package com.mirboard.domain.game.onecard.rules;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import java.util.function.IntPredicate;

/** 다음 차례 (`docs/rules-onecard.md` §8.2). 살아 있는 좌석만 센다(§2). */
public final class TurnOrder {

    private TurnOrder() {
    }

    /** from 다음으로 direction 쪽에 있는 살아 있는 좌석. from 자신은 한 바퀴 돌아 마지막에 본다. */
    public static int nextAlive(int seatCount, IntPredicate alive, int from, int direction) {
        for (int step = 1; step <= seatCount; step++) {
            int seat = Math.floorMod(from + direction * step, seatCount);
            if (alive.test(seat)) {
                return seat;
            }
        }
        throw new IllegalStateException("no live seat");
    }

    /**
     * 방금 낸 카드 다음의 차례 (§8.2). Q 는 방향을 이미 뒤집은 뒤의 {@code direction} 을 받는다.
     * 살아 있는 사람이 2명이면 J·Q 도 "한 번 더"다 (§8.1).
     */
    public static int afterPlay(int seatCount, IntPredicate alive, int seat, int direction, PlayingCard card) {
        if (card.isExtraTurn()) {
            return seat;
        }
        boolean twoLeft = countAlive(seatCount, alive) == 2;
        if (card.isSkip()) {
            int skipped = nextAlive(seatCount, alive, seat, direction);
            return twoLeft ? seat : nextAlive(seatCount, alive, skipped, direction);
        }
        if (card.isReverse() && twoLeft) {
            return seat;
        }
        return nextAlive(seatCount, alive, seat, direction);
    }

    private static int countAlive(int seatCount, IntPredicate alive) {
        int count = 0;
        for (int seat = 0; seat < seatCount; seat++) {
            if (alive.test(seat)) {
                count++;
            }
        }
        return count;
    }
}
```

`server/src/main/java/com/mirboard/domain/game/onecard/rules/Ranking.java`:

```java
package com.mirboard.domain.game.onecard.rules;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult.SeatStatus;
import com.mirboard.domain.game.onecard.state.MatchResult.Standing;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 순위 (`docs/rules-onecard.md` §11.2). 다 낸 사람 → 살아 있는 사람(남은 장수 적은 순) → 파산자(늦게 파산한
 * 쪽이 위) → 탈주자(서로 동순위). 동순위 다음 순위는 건너뛴다(1, 1, 3).
 */
public final class Ranking {

    private Ranking() {
    }

    /**
     * @param hands        좌석별 손패(탈락자는 빈 목록)
     * @param eliminations 탈락 순서
     * @param finisher     마지막 카드를 낸 좌석, 없으면 −1
     * @return 좌석마다 한 줄, 순위 오름차순(같은 순위는 좌석 오름차순)
     */
    public static List<Standing> rank(List<List<PlayingCard>> hands, List<Elimination> eliminations, int finisher) {
        List<Entry> entries = new ArrayList<>();
        for (int seat = 0; seat < hands.size(); seat++) {
            Elimination out = find(eliminations, seat);
            if (seat == finisher) {
                entries.add(new Entry(seat, 0, 0, 0, SeatStatus.FINISHED));
            } else if (out == null) {
                int left = hands.get(seat).size();
                entries.add(new Entry(seat, 1, left, left, SeatStatus.ALIVE));
            } else if (out.reason() == Elimination.Reason.BANKRUPT) {
                entries.add(new Entry(seat, 2, -eliminations.indexOf(out), out.cardsHeld(), SeatStatus.BANKRUPT));
            } else {
                entries.add(new Entry(seat, 3, 0, out.cardsHeld(), SeatStatus.DESERTED));
            }
        }
        Comparator<Entry> order = Comparator.comparingInt(Entry::group).thenComparingInt(Entry::key);
        List<Standing> standings = new ArrayList<>();
        for (Entry entry : entries) {
            int ahead = (int) entries.stream().filter(other -> order.compare(other, entry) < 0).count();
            standings.add(new Standing(entry.seat(), ahead + 1, entry.cardsLeft(), entry.status()));
        }
        standings.sort(Comparator.comparingInt(Standing::rank).thenComparingInt(Standing::seat));
        return List.copyOf(standings);
    }

    private static Elimination find(List<Elimination> eliminations, int seat) {
        return eliminations.stream().filter(e -> e.seat() == seat).findFirst().orElse(null);
    }

    /** group 이 앞설수록, 같은 group 이면 key 가 작을수록 위다. */
    private record Entry(int seat, int group, int key, int cardsLeft, SeatStatus status) {
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: PASS — 원카드 테스트 누적 40건(PlayRules 9 · TurnOrder 8 · Ranking 4).

- [ ] **Step 5: 커밋**

```bash
git add server/src/main/java/com/mirboard/domain/game/onecard/rules \
  server/src/test/java/com/mirboard/domain/game/onecard/rules
git commit -m "feat(D-127): 원카드 규칙 — 낼 수 있는 카드·다음 차례·순위

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 5: 엔진 — 시작·내기·먹기·파산·종료 + 불변식 검사기

이 태스크의 엔진은 **경쟁 창과 탈주가 없는 버전**이다 — 1장이 남아도 차례가 그냥 넘어가고, `CALL_ONE_CARD`·`CATCH`
는 `NO_RACE` 로 거절한다. Task 6 이 경쟁 창을, Task 7 이 탈주를 더한다. 아래 테스트는 1장이 남는 상황을 만들지
않으므로 세 단계 모두에서 그대로 통과한다.

**Files:**
- Create: `server/src/main/java/com/mirboard/domain/game/onecard/OneCardEngine.java`
- Create: `server/src/main/java/com/mirboard/domain/game/onecard/invariant/OneCardInvariantChecker.java`
- Create: `server/src/test/java/com/mirboard/domain/game/onecard/OneCardEnginePlayTest.java`, `server/src/test/java/com/mirboard/domain/game/onecard/invariant/OneCardInvariantCheckerTest.java`

**Interfaces:**
- Consumes: Task 1~4 전부.
- Produces: `OneCardEngine(GameContext context, Random rng)` — 상수 `BANKRUPTCY_HAND_SIZE=20`·`TURN_LIMIT=600`,
  `record Result(OneCardState newState, List<OneCardEvent> events)`, `startMatch()`·`startMatch(Dealer.Shuffler)`,
  `apply(OneCardState state, int seat, OneCardAction action, long now)`(거절은 `OneCardActionRejectedException`;
  `now` 는 Task 6 에서 경쟁 창을 열 때 쓴다), `pendingSeats(state)`·`legalActions(state, seat)`·
  `timeoutAction(state, seat)`. `OneCardInvariantChecker.check(OneCardState)` — 깨지면 `IllegalStateException`.

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardEnginePlayTest.java`:

```java
package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.BLACK_JOKER;
import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.action.OneCardAction.Draw;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.action.OneCardActionRejectedException;
import com.mirboard.domain.game.onecard.action.RejectionReason;
import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.event.OneCardEvent.CardPlayed;
import com.mirboard.domain.game.onecard.event.OneCardEvent.CardsDrawn;
import com.mirboard.domain.game.onecard.event.OneCardEvent.DrawReason;
import com.mirboard.domain.game.onecard.event.OneCardEvent.HandDealt;
import com.mirboard.domain.game.onecard.event.OneCardEvent.HandUpdated;
import com.mirboard.domain.game.onecard.event.OneCardEvent.MatchEnded;
import com.mirboard.domain.game.onecard.event.OneCardEvent.MatchStarted;
import com.mirboard.domain.game.onecard.event.OneCardEvent.PileReshuffled;
import com.mirboard.domain.game.onecard.event.OneCardEvent.PlayerEliminated;
import com.mirboard.domain.game.onecard.event.OneCardEvent.TurnChanged;
import com.mirboard.domain.game.onecard.invariant.OneCardInvariantChecker;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** `docs/rules-onecard.md` §2~§8, §10~§11 — 시작·내기·먹기·파산·종료. 경쟁 창은 {@code OneCardEngineRaceTest}. */
class OneCardEnginePlayTest {

    private static final long NOW = 1_000L;

    private static OneCardEngine engine(int seats, Integer... botSeats) {
        List<Long> ids = LongStream.range(0, seats).map(i -> 100 + i).boxed().toList();
        return new OneCardEngine(new GameContext("r", ids, 0, 0, List.of(botSeats)), new Random(7));
    }

    private static OneCardState play(OneCardEngine engine, OneCardState state, int seat, PlayingCard card) {
        return engine.apply(state, seat, PlayCard.of(card), NOW).newState();
    }

    private static RejectionReason rejection(OneCardEngine engine, OneCardState state, int seat,
                                             OneCardAction action) {
        try {
            engine.apply(state, seat, action, NOW);
        } catch (OneCardActionRejectedException e) {
            return e.reason();
        }
        throw new AssertionError("expected a rejection");
    }

    // ---------- 시작 (§2, §3) ----------

    @Test
    void start_deals_seven_each_flips_a_normal_card_and_announces_the_first_turn() {
        OneCardEngine.Result result = engine(4).startMatch();
        OneCardState state = result.newState();

        assertThat(state.hands()).allSatisfy(h -> assertThat(h).hasSize(Dealer.HAND_SIZE));
        assertThat(state.topCard().isNormal()).isTrue();
        assertThat(result.events().getFirst()).isInstanceOf(MatchStarted.class);
        assertThat(result.events()).filteredOn(HandDealt.class::isInstance).hasSize(4)
                .allSatisfy(e -> assertThat(e.isPrivate()).isTrue());
        assertThat(result.events().getLast()).isEqualTo(new TurnChanged(state.turnSeat(), 1, 0));
        OneCardInvariantChecker.check(state);
    }

    @Test
    void the_first_seat_is_random() {
        Set<Integer> firstSeats = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            List<Long> ids = List.of(1L, 2L, 3L, 4L);
            OneCardEngine engine = new OneCardEngine(new GameContext("r", ids), new Random(seed));
            firstSeats.add(engine.startMatch().newState().turnSeat());
        }
        assertThat(firstSeats).containsExactlyInAnyOrder(0, 1, 2, 3);
    }

    // ---------- 내기 (§5, §8) ----------

    @Test
    void a_normal_card_passes_the_turn_and_only_the_hand_event_is_private() {
        OneCardState state = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();

        OneCardEngine.Result result = engine(2).apply(state, 0, PlayCard.of(heart(9)), NOW);

        assertThat(result.newState().topCard()).isEqualTo(heart(9));
        assertThat(result.newState().turnSeat()).isEqualTo(1);
        assertThat(result.events()).containsExactly(
                new CardPlayed(0, heart(9), null, 2, 0),
                new HandUpdated(0, List.of(club(3), club(4)), List.of(), 2),
                new TurnChanged(1, 1, 0));
        assertThat(result.events()).filteredOn(OneCardEvent::isPrivate).hasSize(1);
        OneCardInvariantChecker.check(result.newState());
    }

    @Test
    void a_seven_sets_the_base_suit_for_the_next_player() {
        OneCardState state = seats(hand(heart(7), club(3), club(4)), hand(spade(4), heart(9), spade(8)))
                .top(heart(5)).turn(0).build();
        OneCardEngine engine = engine(2);

        OneCardState next = engine.apply(state, 0, new PlayCard(heart(7), Suit.SPADE), NOW).newState();

        assertThat(next.declaredSuit()).isEqualTo(Suit.SPADE);
        assertThat(rejection(engine, next, 1, PlayCard.of(heart(9)))).isEqualTo(RejectionReason.CARD_NOT_PLAYABLE);
        assertThat(play(engine, next, 1, spade(4)).declaredSuit()).isNull();
    }

    @Test
    void a_suit_declaration_is_required_on_a_seven_and_forbidden_elsewhere() {
        OneCardState state = seats(hand(heart(7), heart(9), club(4)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();
        OneCardEngine engine = engine(2);

        assertThat(rejection(engine, state, 0, PlayCard.of(heart(7))))
                .isEqualTo(RejectionReason.INVALID_SUIT_DECLARATION);
        assertThat(rejection(engine, state, 0, new PlayCard(heart(9), Suit.CLUB)))
                .isEqualTo(RejectionReason.INVALID_SUIT_DECLARATION);
    }

    @Test
    void basic_rejections_name_the_reason() {
        OneCardState state = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();
        OneCardEngine engine = engine(2);

        assertThat(rejection(engine, state, 1, PlayCard.of(spade(4)))).isEqualTo(RejectionReason.NOT_YOUR_TURN);
        assertThat(rejection(engine, state, 0, PlayCard.of(spade(4)))).isEqualTo(RejectionReason.CARD_NOT_OWNED);
        assertThat(rejection(engine, state, 0, PlayCard.of(club(3)))).isEqualTo(RejectionReason.CARD_NOT_PLAYABLE);
    }

    @Test
    void an_attack_passes_to_the_next_seat_which_must_counter_or_draw_the_stack() {
        OneCardState state = seats(hand(heart(2), club(3), club(4)), hand(spade(2), heart(9), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();
        OneCardEngine engine = engine(3);

        OneCardEngine.Result attacked = engine.apply(state, 0, PlayCard.of(heart(2)), NOW);
        assertThat(attacked.events().getLast()).isEqualTo(new TurnChanged(1, 1, 2));
        assertThat(rejection(engine, attacked.newState(), 1, PlayCard.of(heart(9))))
                .isEqualTo(RejectionReason.COUNTER_REQUIRED);

        OneCardState countered = play(engine, attacked.newState(), 1, spade(2));
        assertThat(countered.attackStack()).isEqualTo(4);
        assertThat(countered.turnSeat()).isEqualTo(2);

        OneCardEngine.Result drew = engine.apply(countered, 2, new Draw(), NOW);
        assertThat(drew.newState().hands().get(2)).hasSize(7);
        assertThat(drew.newState().attackStack()).isZero();
        assertThat(drew.newState().turnSeat()).isEqualTo(0);
        assertThat(drew.events()).contains(new CardsDrawn(2, 4, DrawReason.ATTACK, 7,
                drew.newState().drawPile().size()));
    }

    @Test
    void a_jack_skips_the_next_seat() {
        OneCardState state = seats(hand(heart(PlayingCard.JACK), club(3), club(4)),
                hand(spade(4), spade(6), spade(8)), hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();

        assertThat(play(engine(3), state, 0, heart(PlayingCard.JACK)).turnSeat()).isEqualTo(2);
    }

    @Test
    void a_queen_reverses_the_direction() {
        OneCardState state = seats(hand(heart(PlayingCard.QUEEN), club(3), club(4)),
                hand(spade(4), spade(6), spade(8)), hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();

        OneCardState next = play(engine(3), state, 0, heart(PlayingCard.QUEEN));

        assertThat(next.direction()).isEqualTo(-1);
        assertThat(next.turnSeat()).isEqualTo(2);
    }

    @Test
    void a_king_gives_another_turn_and_drawing_then_ends_it() {
        OneCardState state = seats(hand(heart(PlayingCard.KING), club(3), club(4)),
                hand(spade(4), spade(6), spade(8)), hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();
        OneCardEngine engine = engine(3);

        OneCardState again = play(engine, state, 0, heart(PlayingCard.KING));
        assertThat(again.turnSeat()).isZero();

        assertThat(engine.apply(again, 0, new Draw(), NOW).newState().turnSeat()).isEqualTo(1);
    }

    @Test
    void with_two_players_jack_and_queen_give_another_turn() {
        OneCardEngine engine = engine(2);
        OneCardState jack = seats(hand(heart(PlayingCard.JACK), club(3), club(4)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();
        OneCardState queen = seats(hand(heart(PlayingCard.QUEEN), club(3), club(4)),
                hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();

        assertThat(play(engine, jack, 0, heart(PlayingCard.JACK)).turnSeat()).isZero();
        OneCardState afterQueen = play(engine, queen, 0, heart(PlayingCard.QUEEN));
        assertThat(afterQueen.turnSeat()).isZero();
        assertThat(afterQueen.direction()).isEqualTo(-1);
    }

    // ---------- 먹기 (§7) ----------

    @Test
    void drawing_takes_one_card_even_when_a_card_could_be_played() {
        OneCardState state = seats(hand(heart(9), club(3)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).drawPile(diamond(9), diamond(10)).turn(0).build();

        OneCardEngine.Result result = engine(2).apply(state, 0, new Draw(), NOW);

        assertThat(result.newState().hands().get(0)).containsExactly(heart(9), club(3), diamond(9));
        assertThat(result.events()).containsExactly(
                new CardsDrawn(0, 1, DrawReason.TURN, 3, 1),
                new HandUpdated(0, List.of(heart(9), club(3), diamond(9)), List.of(diamond(9)), 2),
                new TurnChanged(1, 1, 0));
    }

    @Test
    void an_empty_pile_is_refilled_from_the_discard_pile_keeping_the_top() {
        OneCardState state = seats(hand(club(3), club(4)), hand(spade(4), spade(6), spade(8)))
                .top(heart(2)).attack(2).drawPile(diamond(9)).turn(0).build();

        OneCardEngine.Result result = engine(2).apply(state, 0, new Draw(), NOW);

        assertThat(result.newState().hands().get(0)).hasSize(4);
        assertThat(result.newState().discardPile()).containsExactly(heart(2));
        assertThat(result.events()).anyMatch(PileReshuffled.class::isInstance);
        OneCardInvariantChecker.check(result.newState());
    }

    @Test
    void with_nothing_left_to_draw_the_turn_is_a_pass_and_everyone_passing_is_a_stalemate() {
        OneCardState state = everyCardInHands(3, heart(5));
        OneCardEngine engine = engine(3);

        OneCardEngine.Result first = engine.apply(state, 0, new Draw(), NOW);
        assertThat(first.newState().passStreak()).isEqualTo(1);
        assertThat(first.events()).contains(new CardsDrawn(0, 0, DrawReason.TURN, 18, 0));

        OneCardState second = engine.apply(first.newState(), 1, new Draw(), NOW).newState();
        OneCardEngine.Result third = engine.apply(second, 2, new Draw(), NOW);
        assertThat(third.newState().result().reason()).isEqualTo(EndReason.STALEMATE);
        assertThat(third.events().getLast()).isInstanceOf(MatchEnded.class);
    }

    /** 맨 위 한 장을 빼고 53장을 전부 손패로 나눈 상태 — 뽑을 더미도, 다시 채울 버린 더미도 없다. */
    private static OneCardState everyCardInHands(int seatCount, PlayingCard top) {
        List<List<PlayingCard>> hands = new ArrayList<>();
        for (int seat = 0; seat < seatCount; seat++) {
            hands.add(new ArrayList<>());
        }
        int next = 0;
        for (PlayingCard card : Deck.all()) {
            if (!card.equals(top)) {
                hands.get(next++ % seatCount).add(card);
            }
        }
        return new OneCardState(hands, List.of(), List.of(top), 0, 1, null, 0, null, List.of(), 0, 0, 1, null);
    }

    @Test
    void reaching_twenty_cards_is_bankruptcy_and_two_players_leaves_one_standing() {
        List<PlayingCard> nineteen = new ArrayList<>(
                IntStream.rangeClosed(1, 13).mapToObj(OneCardTables::club).toList());
        nineteen.addAll(List.of(diamond(1), diamond(3), diamond(4), diamond(6), diamond(8), diamond(9)));
        OneCardState state = seats(nineteen, hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).drawPile(diamond(10), diamond(11)).turn(0).build();

        OneCardEngine.Result result = engine(2).apply(state, 0, new Draw(), NOW);

        OneCardState next = result.newState();
        assertThat(next.hands().get(0)).isEmpty();
        assertThat(next.drawPile()).endsWith(diamond(10));
        assertThat(next.eliminations()).containsExactly(new Elimination(0, Elimination.Reason.BANKRUPT, 20));
        assertThat(result.events()).contains(new PlayerEliminated(0, Elimination.Reason.BANKRUPT, 20));
        assertThat(next.result().reason()).isEqualTo(EndReason.LAST_STANDING);
        assertThat(next.result().winners()).containsExactly(1);
        OneCardInvariantChecker.check(next);
    }

    // ---------- 종료 (§11) ----------

    @Test
    void playing_the_last_card_wins_even_if_it_is_an_attack_card() {
        OneCardState state = seats(hand(heart(2)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();

        OneCardEngine.Result result = engine(2).apply(state, 0, PlayCard.of(heart(2)), NOW);

        assertThat(result.newState().result().reason()).isEqualTo(EndReason.FINISHED);
        assertThat(result.newState().result().winners()).containsExactly(0);
        assertThat(result.newState().turnSeat()).isEqualTo(-1);
        assertThat(engine(2).pendingSeats(result.newState())).isEmpty();
    }

    @Test
    void the_turn_limit_ends_the_match_as_a_stalemate() {
        OneCardState state = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).turnCount(OneCardEngine.TURN_LIMIT - 1).build();

        OneCardState next = play(engine(2), state, 0, heart(9));

        assertThat(next.result().reason()).isEqualTo(EndReason.STALEMATE);
    }

    @Test
    void after_the_end_and_for_eliminated_seats_actions_are_rejected() {
        OneCardEngine engine = engine(3);
        OneCardState ended = engine.apply(seats(hand(heart(2)), hand(spade(4), spade(6)), hand(club(9), club(10)))
                .top(heart(5)).turn(0).build(), 0, PlayCard.of(heart(2)), NOW).newState();
        OneCardState withOut = seats(hand(heart(9), club(3), club(4)), hand(), hand(diamond(4), diamond(6)))
                .eliminated(1, Elimination.Reason.BANKRUPT, 20).top(heart(5)).turn(0).build();

        assertThat(rejection(engine, ended, 1, new Draw())).isEqualTo(RejectionReason.MATCH_OVER);
        assertThat(rejection(engine, withOut, 1, new Draw())).isEqualTo(RejectionReason.PLAYER_ELIMINATED);
    }

    // ---------- 봇·타임아웃용 질의 ----------

    @Test
    void legal_actions_list_every_playable_card_each_seven_suit_and_drawing() {
        OneCardState state = seats(hand(heart(9), heart(7), club(3)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();

        List<OneCardAction> legal = engine(2).legalActions(state, 0);

        assertThat(legal).containsExactlyInAnyOrder(
                PlayCard.of(heart(9)),
                new PlayCard(heart(7), Suit.SPADE), new PlayCard(heart(7), Suit.HEART),
                new PlayCard(heart(7), Suit.DIAMOND), new PlayCard(heart(7), Suit.CLUB),
                new Draw());
        assertThat(engine(2).legalActions(state, 1)).isEmpty();
    }

    @Test
    void under_attack_only_counters_and_drawing_are_legal() {
        OneCardState state = seats(hand(spade(2), heart(9), BLACK_JOKER), hand(spade(4), spade(6), spade(8)))
                .top(heart(2)).attack(2).turn(0).build();

        assertThat(engine(2).legalActions(state, 0)).containsExactlyInAnyOrder(
                PlayCard.of(spade(2)), PlayCard.of(BLACK_JOKER), new Draw());
    }

    @Test
    void the_timeout_action_is_drawing_for_the_seat_on_turn_only() {
        OneCardState state = seats(hand(heart(9), club(3)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();

        assertThat(engine(2).timeoutAction(state, 0)).isEqualTo(new Draw());
        assertThat(engine(2).timeoutAction(state, 1)).isNull();
    }
}
```

`server/src/test/java/com/mirboard/domain/game/onecard/invariant/OneCardInvariantCheckerTest.java`:

```java
package com.mirboard.domain.game.onecard.invariant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** `docs/rules-onecard.md` §14 — 불변식 검사기가 깨진 상태를 실제로 잡는가. */
class OneCardInvariantCheckerTest {

    private static final PlayingCard TOP = PlayingCard.of(Suit.HEART, 5);

    /** 좌석 0·1 에 각자 3장, 나머지는 뽑을 더미, 맨 위는 ♥5. */
    private static OneCardState valid() {
        List<PlayingCard> rest = new ArrayList<>(Deck.all());
        rest.remove(TOP);
        List<PlayingCard> seatZero = new ArrayList<>(rest.subList(0, 3));
        List<PlayingCard> seatOne = new ArrayList<>(rest.subList(3, 6));
        List<PlayingCard> pile = new ArrayList<>(rest.subList(6, rest.size()));
        return new OneCardState(List.of(seatZero, seatOne), pile, List.of(TOP), 0, 1, null, 0, null, List.of(),
                0, 0, 1, null);
    }

    private static OneCardState with(OneCardState s, List<PlayingCard> drawPile, int attackStack,
                                     List<Elimination> eliminations, int turnSeat) {
        return new OneCardState(s.hands(), drawPile, s.discardPile(), turnSeat, s.direction(), s.declaredSuit(),
                attackStack, s.race(), eliminations, s.passStreak(), s.turnCount(), s.version(), s.result());
    }

    @Test
    void a_valid_table_passes() {
        assertThatCode(() -> OneCardInvariantChecker.check(valid())).doesNotThrowAnyException();
    }

    @Test
    void a_lost_card_is_caught() {
        OneCardState s = valid();
        OneCardState broken = with(s, s.drawPile().subList(1, s.drawPile().size()), 0, List.of(), 0);

        assertThatThrownBy(() -> OneCardInvariantChecker.check(broken)).hasMessageContaining("card conservation");
    }

    @Test
    void an_eliminated_seat_holding_cards_is_caught() {
        OneCardState s = valid();
        OneCardState broken = with(s, s.drawPile(), 0, List.of(new Elimination(1, Elimination.Reason.DESERTED, 3)), 0);

        assertThatThrownBy(() -> OneCardInvariantChecker.check(broken)).hasMessageContaining("still holds cards");
    }

    @Test
    void a_pending_attack_on_a_non_attack_card_is_caught() {
        OneCardState s = valid();
        OneCardState broken = with(s, s.drawPile(), 2, List.of(), 0);

        assertThatThrownBy(() -> OneCardInvariantChecker.check(broken)).hasMessageContaining("attack pending");
    }

    @Test
    void a_turn_on_a_missing_seat_is_caught() {
        OneCardState s = valid();
        OneCardState broken = with(s, s.drawPile(), 0, List.of(), 5);

        assertThatThrownBy(() -> OneCardInvariantChecker.check(broken)).hasMessageContaining("turn seat");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: FAIL — `compileTestJava` 에서 `cannot find symbol`(`OneCardEngine`·`OneCardInvariantChecker` 가 아직 없다).

- [ ] **Step 3: 구현 — 엔진(경쟁 창·탈주 없는 버전)**

`server/src/main/java/com/mirboard/domain/game/onecard/OneCardEngine.java`:

```java
package com.mirboard.domain.game.onecard;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.action.OneCardActionRejectedException;
import com.mirboard.domain.game.onecard.action.RejectionReason;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.event.OneCardEvent.DrawReason;
import com.mirboard.domain.game.onecard.rules.PlayRules;
import com.mirboard.domain.game.onecard.rules.Ranking;
import com.mirboard.domain.game.onecard.rules.TurnOrder;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 원카드 <b>순수 룰 엔진</b> (`docs/rules-onecard.md`).
 *
 * <p>Spring·Redis·시계를 모른다. 시각이 필요한 곳(경쟁 창을 여는 순간)은 {@code now} 를 인자로 받고, 난수는
 * 생성자로 주입받는다 — 테스트는 시드를 고정한다. 포트 어댑터·상태 저장·뷰·봇 정책·기록은 S3 범위다(스컬킹의
 * {@code SkullKingEngine} + {@code SkullKingGameEngine} 과 같은 2계층).
 *
 * <p>상태는 불변 레코드다. 한 전이가 여러 필드를 함께 바꾸므로 엔진 안에서만 가변 사본({@code Table})을 만들어
 * 고친 뒤 새 레코드로 얼린다 — 밖에서 보이는 것은 언제나 새 {@link OneCardState} 다.
 */
public final class OneCardEngine {

    /** 먹은 뒤 이 장수 이상이면 파산 (§7, §10). */
    public static final int BANKRUPTCY_HAND_SIZE = 20;

    /** 총 차례 상한 (§11.3). */
    public static final int TURN_LIMIT = 600;

    private final GameContext context;
    private final Random rng;

    public OneCardEngine(GameContext context, Random rng) {
        this.context = context;
        this.rng = rng;
    }

    public GameContext context() {
        return context;
    }

    /** 전이 결과 — 새 상태 + 발행할 이벤트. */
    public record Result(OneCardState newState, List<OneCardEvent> events) {
        public Result {
            events = List.copyOf(events);
        }
    }

    // ---------- 시작 (§2, §3) ----------

    public Result startMatch() {
        return startMatch(Dealer.random(rng));
    }

    /** 분배하고 첫 차례를 무작위로 고른다. 셔플러는 §3-5 테스트를 위해 주입할 수 있다. */
    public Result startMatch(Dealer.Shuffler shuffler) {
        int seatCount = context.seatCount();
        Dealer.Deal deal = Dealer.deal(seatCount, shuffler);
        int first = rng.nextInt(seatCount);
        OneCardState state = new OneCardState(deal.hands(), deal.drawPile(), List.of(deal.startCard()),
                first, +1, null, 0, null, List.of(), 0, 0, 1, null);

        List<OneCardEvent> events = new ArrayList<>();
        events.add(new OneCardEvent.MatchStarted(first, deal.startCard(), Dealer.HAND_SIZE, deal.drawPile().size()));
        for (int seat = 0; seat < seatCount; seat++) {
            events.add(new OneCardEvent.HandDealt(seat, deal.hands().get(seat), state.version()));
        }
        events.add(new OneCardEvent.TurnChanged(first, +1, 0));
        return new Result(state, events);
    }

    // ---------- 액션 (§4 ~ §9) ----------

    /**
     * @param now 지금 시각(epoch ms). 경쟁 창을 열 때만 쓴다
     * @throws OneCardActionRejectedException 룰 위반
     */
    public Result apply(OneCardState state, int seat, OneCardAction action, long now) {
        if (state.ended()) {
            throw rejected(RejectionReason.MATCH_OVER);
        }
        if (seat < 0 || seat >= state.seatCount()) {
            throw new IllegalArgumentException("no such seat: " + seat);
        }
        if (!state.alive(seat)) {
            throw rejected(RejectionReason.PLAYER_ELIMINATED);
        }
        return switch (action) {
            case OneCardAction.PlayCard play -> play(state, seat, play, now);
            case OneCardAction.Draw __ -> draw(state, seat);
            // 경쟁 창은 다음 태스크에서 연다 — 지금은 열린 창이 있을 수 없다.
            case OneCardAction.CallOneCard __ -> throw rejected(RejectionReason.NO_RACE);
            case OneCardAction.Catch __ -> throw rejected(RejectionReason.NO_RACE);
        };
    }

    private Result play(OneCardState state, int seat, OneCardAction.PlayCard action, long now) {
        requireTurn(state, seat);
        PlayingCard card = action.card();
        if (card == null || !state.hands().get(seat).contains(card)) {
            throw rejected(RejectionReason.CARD_NOT_OWNED);
        }
        if (card.isSuitChange() != (action.declaredSuit() != null)) {
            throw rejected(RejectionReason.INVALID_SUIT_DECLARATION);
        }
        if (!PlayRules.canPlay(state.topCard(), state.baseSuit(), state.attackStack(), card)) {
            throw rejected(state.attackStack() > 0
                    ? RejectionReason.COUNTER_REQUIRED : RejectionReason.CARD_NOT_PLAYABLE);
        }

        Table t = new Table(state);
        List<PlayingCard> hand = t.hands.get(seat);
        hand.remove(card);
        t.discardPile.add(card);
        t.declaredSuit = action.declaredSuit();
        if (card.isAttack()) {
            t.attackStack += card.attackValue();
        }
        if (card.isReverse()) {
            t.direction = -t.direction;
        }
        t.passStreak = 0;
        t.turnCount++;
        t.version++;

        List<OneCardEvent> events = new ArrayList<>();
        events.add(new OneCardEvent.CardPlayed(seat, card, t.declaredSuit, hand.size(), t.attackStack));
        events.add(new OneCardEvent.HandUpdated(seat, hand, List.of(), t.version));

        if (hand.isEmpty()) {
            finish(t, EndReason.FINISHED, seat, events);
        } else if (t.turnCount >= TURN_LIMIT) {
            finish(t, EndReason.STALEMATE, -1, events);
        } else {
            passTurn(t, TurnOrder.afterPlay(t.seatCount(), t::alive, seat, t.direction, card), events);
        }
        return new Result(t.freeze(), events);
    }

    private Result draw(OneCardState state, int seat) {
        requireTurn(state, seat);
        Table t = new Table(state);
        boolean attacked = t.attackStack > 0;
        List<OneCardEvent> events = new ArrayList<>();
        List<PlayingCard> drawn = drawFromPile(t, attacked ? t.attackStack : 1, events);
        List<PlayingCard> hand = t.hands.get(seat);
        hand.addAll(drawn);
        t.attackStack = 0;
        t.turnCount++;
        t.passStreak = drawn.isEmpty() ? t.passStreak + 1 : 0;
        t.version++;
        events.add(new OneCardEvent.CardsDrawn(seat, drawn.size(),
                attacked ? DrawReason.ATTACK : DrawReason.TURN, hand.size(), t.drawPile.size()));
        events.add(new OneCardEvent.HandUpdated(seat, hand, drawn, t.version));

        if (hand.size() >= BANKRUPTCY_HAND_SIZE) {
            eliminate(t, seat, Elimination.Reason.BANKRUPT, events);
            if (endIfDecided(t, events)) {
                return new Result(t.freeze(), events);
            }
        }
        if (t.passStreak >= t.aliveCount() || t.turnCount >= TURN_LIMIT) {
            finish(t, EndReason.STALEMATE, -1, events);
        } else {
            passTurn(t, TurnOrder.nextAlive(t.seatCount(), t::alive, seat, t.direction), events);
        }
        return new Result(t.freeze(), events);
    }

    private static void requireTurn(OneCardState state, int seat) {
        if (state.race() != null) {
            throw rejected(RejectionReason.RACE_IN_PROGRESS);
        }
        if (state.turnSeat() != seat) {
            throw rejected(RejectionReason.NOT_YOUR_TURN);
        }
    }

    // ---------- 진행 질의 ----------

    /** 지금 행동을 기다리는 좌석. 경쟁 창이 열렸거나 끝났으면 비어 있다(설계서 §4.5). */
    public List<Integer> pendingSeats(OneCardState state) {
        if (state.ended() || state.race() != null || state.turnSeat() < 0) {
            return List.of();
        }
        return List.of(state.turnSeat());
    }

    /** 그 좌석이 지금 할 수 있는 액션 전부. 7 은 지정 무늬마다 다른 액션이다. */
    public List<OneCardAction> legalActions(OneCardState state, int seat) {
        if (state.ended() || seat < 0 || seat >= state.seatCount() || !state.alive(seat)) {
            return List.of();
        }
        if (state.turnSeat() != seat) {
            return List.of();
        }
        List<OneCardAction> actions = new ArrayList<>();
        for (PlayingCard card : state.hands().get(seat)) {
            if (!PlayRules.canPlay(state.topCard(), state.baseSuit(), state.attackStack(), card)) {
                continue;
            }
            if (card.isSuitChange()) {
                for (Suit suit : Suit.values()) {
                    actions.add(new OneCardAction.PlayCard(card, suit));
                }
            } else {
                actions.add(OneCardAction.PlayCard.of(card));
            }
        }
        actions.add(new OneCardAction.Draw());
        return List.copyOf(actions);
    }

    /** 턴 제한 초과 시의 안전 액션 — 먹기(§4). 차례가 아니면 null. */
    public OneCardAction timeoutAction(OneCardState state, int seat) {
        return pendingSeats(state).contains(seat) ? new OneCardAction.Draw() : null;
    }

    // ---------- helpers ----------

    /** §7, §7.1 — 모자라면 버린 더미의 맨 위만 남기고 섞어 채운다. 그래도 모자라면 있는 만큼만. */
    private List<PlayingCard> drawFromPile(Table t, int count, List<OneCardEvent> events) {
        List<PlayingCard> drawn = new ArrayList<>();
        while (drawn.size() < count) {
            if (t.drawPile.isEmpty()) {
                if (t.discardPile.size() <= 1) {
                    break;
                }
                PlayingCard top = t.discardPile.removeLast();
                t.drawPile.addAll(Dealer.random(rng).shuffle(t.discardPile));
                t.discardPile.clear();
                t.discardPile.add(top);
                events.add(new OneCardEvent.PileReshuffled(t.drawPile.size()));
            }
            drawn.add(t.drawPile.removeFirst());
        }
        return drawn;
    }

    /**
     * §10 — 손패를 섞지 않고 뽑을 더미 맨 아래로. 연속 패스 수는 0 으로(§11.3). 같은 전이에서 앞서 보낸 손패
     * 이벤트(먹은 직후의 손패)보다 새 버전이어야 클라가 빈 손패를 버리지 않으므로 버전을 한 번 더 올린다.
     */
    private static void eliminate(Table t, int seat, Elimination.Reason reason, List<OneCardEvent> events) {
        List<PlayingCard> hand = t.hands.get(seat);
        int held = hand.size();
        t.drawPile.addAll(hand);
        hand.clear();
        t.eliminations.add(new Elimination(seat, reason, held));
        t.passStreak = 0;
        if (t.turnSeat == seat) {
            t.turnSeat = -1;
        }
        t.version++;
        events.add(new OneCardEvent.PlayerEliminated(seat, reason, held));
        events.add(new OneCardEvent.HandUpdated(seat, List.of(), List.of(), t.version));
    }

    /** 탈락 뒤 종료 판정 — 1명만 남았으면 LAST_STANDING, 살아 있는 사람이 없으면 NO_HUMANS (§11.1). */
    private boolean endIfDecided(Table t, List<OneCardEvent> events) {
        if (t.aliveCount() == 1) {
            finish(t, EndReason.LAST_STANDING, -1, events);
            return true;
        }
        boolean humanAlive = false;
        for (int seat = 0; seat < t.seatCount(); seat++) {
            if (t.alive(seat) && !context.botSeats().contains(seat)) {
                humanAlive = true;
                break;
            }
        }
        if (!humanAlive) {
            finish(t, EndReason.NO_HUMANS, -1, events);
            return true;
        }
        return false;
    }

    private static void finish(Table t, EndReason reason, int finisher, List<OneCardEvent> events) {
        MatchResult result = new MatchResult(reason, Ranking.rank(t.hands, t.eliminations, finisher));
        t.result = result;
        t.turnSeat = -1;
        t.race = null;
        events.add(new OneCardEvent.MatchEnded(reason, result.standings()));
    }

    private static void passTurn(Table t, int seat, List<OneCardEvent> events) {
        t.turnSeat = seat;
        events.add(new OneCardEvent.TurnChanged(seat, t.direction, t.attackStack));
    }

    private static OneCardActionRejectedException rejected(RejectionReason reason) {
        return new OneCardActionRejectedException(reason);
    }

    /** 한 전이 동안만 쓰는 가변 사본. 밖으로는 {@link #freeze()} 한 레코드만 나간다. */
    private static final class Table {

        final List<List<PlayingCard>> hands = new ArrayList<>();
        final List<PlayingCard> drawPile;
        final List<PlayingCard> discardPile;
        final List<Elimination> eliminations;
        int turnSeat;
        int direction;
        Suit declaredSuit;
        int attackStack;
        RaceWindow race;
        int passStreak;
        int turnCount;
        int version;
        MatchResult result;

        Table(OneCardState s) {
            s.hands().forEach(hand -> hands.add(new ArrayList<>(hand)));
            drawPile = new ArrayList<>(s.drawPile());
            discardPile = new ArrayList<>(s.discardPile());
            eliminations = new ArrayList<>(s.eliminations());
            turnSeat = s.turnSeat();
            direction = s.direction();
            declaredSuit = s.declaredSuit();
            attackStack = s.attackStack();
            race = s.race();
            passStreak = s.passStreak();
            turnCount = s.turnCount();
            version = s.version();
            result = s.result();
        }

        int seatCount() {
            return hands.size();
        }

        boolean alive(int seat) {
            return eliminations.stream().noneMatch(e -> e.seat() == seat);
        }

        int aliveCount() {
            return seatCount() - eliminations.size();
        }

        OneCardState freeze() {
            return new OneCardState(hands, drawPile, discardPile, turnSeat, direction, declaredSuit, attackStack,
                    race, eliminations, passStreak, turnCount, version, result);
        }
    }
}
```

- [ ] **Step 4: 구현 — 불변식 검사기**

`server/src/main/java/com/mirboard/domain/game/onecard/invariant/OneCardInvariantChecker.java`:

```java
package com.mirboard.domain.game.onecard.invariant;

import com.mirboard.domain.game.onecard.OneCardEngine;
import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 원카드 상태 불변식 (`docs/rules-onecard.md` §14). 시뮬레이션이 매 전이 뒤 부르고, 깨지면
 * {@link IllegalStateException} 을 던진다.
 */
public final class OneCardInvariantChecker {

    private OneCardInvariantChecker() {
    }

    public static void check(OneCardState s) {
        checkCardConservation(s);
        if (s.discardPile().isEmpty()) {
            fail("discard pile has no top card");
        }
        if (s.direction() != 1 && s.direction() != -1) {
            fail("direction must be ±1: " + s.direction());
        }
        for (Elimination e : s.eliminations()) {
            if (!s.hands().get(e.seat()).isEmpty()) {
                fail("eliminated seat " + e.seat() + " still holds cards");
            }
        }
        for (int seat : s.aliveSeats()) {
            if (s.hands().get(seat).size() >= OneCardEngine.BANKRUPTCY_HAND_SIZE) {
                fail("seat " + seat + " should have gone bankrupt");
            }
        }
        if (s.attackStack() < 0) {
            fail("negative attack stack");
        }
        if (s.attackStack() > 0 && !s.topCard().isAttack()) {
            fail("attack pending but the top card is not an attack card");
        }
        if (s.declaredSuit() != null && !s.topCard().isSuitChange()) {
            fail("declared suit without a 7 on top");
        }
        checkTurn(s);
    }

    /** 54장이 더미·손패에 정확히 한 번씩 (§14). 탈락자 손패는 더미로 갔으므로 0이다. */
    private static void checkCardConservation(OneCardState s) {
        List<PlayingCard> all = new ArrayList<>(s.drawPile());
        all.addAll(s.discardPile());
        s.hands().forEach(all::addAll);
        if (all.size() != Deck.SIZE || !new HashSet<>(all).equals(new HashSet<>(Deck.all()))) {
            fail("card conservation broken: " + all.size() + " cards");
        }
    }

    private static void checkTurn(OneCardState s) {
        RaceWindow race = s.race();
        if (s.ended()) {
            if (s.turnSeat() != -1 || race != null) {
                fail("ended match still has a turn or a race");
            }
            if (s.result().standings().size() != s.seatCount()) {
                fail("standings must cover every seat");
            }
            return;
        }
        if (race != null) {
            if (s.turnSeat() != -1) {
                fail("nobody has the turn while a race is open");
            }
            if (!s.alive(race.ownerSeat()) || s.hands().get(race.ownerSeat()).size() != 1) {
                fail("race owner must be alive with exactly one card");
            }
            if (race.nextSeat() < 0 || race.nextSeat() >= s.seatCount()) {
                fail("race has no next seat");
            }
            return;
        }
        if (s.turnSeat() < 0 || s.turnSeat() >= s.seatCount() || !s.alive(s.turnSeat())) {
            fail("turn seat must be a live seat: " + s.turnSeat());
        }
    }

    private static void fail(String message) {
        throw new IllegalStateException("One Card invariant violated: " + message);
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: PASS — 원카드 테스트 누적 66건(엔진 21 · 불변식 5).

- [ ] **Step 6: 커밋**

```bash
git add server/src/main/java/com/mirboard/domain/game/onecard/OneCardEngine.java \
  server/src/main/java/com/mirboard/domain/game/onecard/invariant \
  server/src/test/java/com/mirboard/domain/game/onecard/OneCardEnginePlayTest.java \
  server/src/test/java/com/mirboard/domain/game/onecard/invariant
git commit -m "feat(D-127): 원카드 엔진 — 시작·내기·먹기·파산·종료 + 불변식 검사기

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 6: 엔진 — 외치기 경쟁 창 (§9)

**Files:**
- Create: `server/src/main/java/com/mirboard/domain/game/onecard/RaceSettings.java`
- Modify: `server/src/main/java/com/mirboard/domain/game/onecard/OneCardEngine.java`
- Create: `server/src/test/java/com/mirboard/domain/game/onecard/OneCardEngineRaceTest.java`

**Interfaces:**
- Consumes: Task 5 엔진, Task 3 `RaceWindow`·`OneCardEvent.RaceOutcome`.
- Produces: `record RaceSettings(long windowMillis, long ownerMinMillis, long ownerMaxMillis, long catcherMinMillis,
  long catcherMaxMillis, int slotCount)` + `DEFAULT`(3초, 1.0~2.5초, 슬롯 8). 엔진에 생성자
  `OneCardEngine(GameContext, Random, RaceSettings)`(2인자 생성자는 `DEFAULT` 로 위임), 상수 `JITTER_RANGE=100`,
  `OptionalLong timerDeadline(OneCardState)`, `Optional<Result> onTimer(OneCardState)`. 1장이 남는 내기는 이제
  창을 연다(`turnSeat=-1`, `RACE_OPENED`), `CALL_ONE_CARD`·`CATCH` 는 창을 닫는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardEngineRaceTest.java`:

```java
package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.action.OneCardAction.CallOneCard;
import com.mirboard.domain.game.onecard.action.OneCardAction.Catch;
import com.mirboard.domain.game.onecard.action.OneCardAction.Draw;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.action.OneCardActionRejectedException;
import com.mirboard.domain.game.onecard.action.RejectionReason;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.event.OneCardEvent.CardsDrawn;
import com.mirboard.domain.game.onecard.event.OneCardEvent.DrawReason;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceOpened;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceOutcome;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceResolved;
import com.mirboard.domain.game.onecard.event.OneCardEvent.TurnChanged;
import com.mirboard.domain.game.onecard.invariant.OneCardInvariantChecker;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** `docs/rules-onecard.md` §9 — 외치기 경쟁 창. */
class OneCardEngineRaceTest {

    private static final long NOW = 50_000L;

    /** 봇 반응 시간을 고정한 설정 — 주인 봇 1.2초, 잡는 봇 1.5초. */
    private static final RaceSettings FIXED = new RaceSettings(3_000, 1_200, 1_200, 1_500, 1_500, 8);

    private static OneCardEngine engine(int seats, RaceSettings settings, Integer... botSeats) {
        List<Long> ids = LongStream.range(0, seats).map(i -> 100 + i).boxed().toList();
        return new OneCardEngine(new GameContext("r", ids, 0, 0, Arrays.asList(botSeats)), new Random(11), settings);
    }

    private static OneCardEngine humans(int seats) {
        return engine(seats, FIXED);
    }

    /** 좌석 0 이 두 장 중 한 장을 내 1장이 되는 테이블(3인). */
    private static OneCardState aboutToGoDownToOne(PlayingCard toPlay) {
        return seats(hand(toPlay, club(3)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();
    }

    private static RejectionReason rejection(OneCardEngine engine, OneCardState state, int seat, OneCardAction action) {
        try {
            engine.apply(state, seat, action, NOW);
        } catch (OneCardActionRejectedException e) {
            return e.reason();
        }
        throw new AssertionError("expected a rejection");
    }

    @Test
    void going_down_to_one_card_opens_a_race_and_pauses_the_turn() {
        OneCardEngine engine = humans(3);

        OneCardEngine.Result result = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW);

        OneCardState state = result.newState();
        RaceWindow race = state.race();
        assertThat(race.ownerSeat()).isZero();
        assertThat(race.nextSeat()).isEqualTo(1);
        assertThat(race.openedAt()).isEqualTo(NOW);
        assertThat(race.slot()).isBetween(0, 7);
        assertThat(race.jitterX()).isBetween(-100, 100);
        assertThat(state.turnSeat()).isEqualTo(-1);
        assertThat(engine.pendingSeats(state)).isEmpty();
        assertThat(result.events()).noneMatch(TurnChanged.class::isInstance);
        assertThat(result.events().getLast()).isEqualTo(new RaceOpened(race.raceId(), 0, race.slot(),
                race.jitterX(), race.jitterY(), 3_000));
        OneCardInvariantChecker.check(state);
    }

    @Test
    void the_owner_calling_first_is_safe_and_the_turn_moves_on() {
        OneCardEngine engine = humans(3);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW).newState();
        int raceId = raced.race().raceId();

        OneCardEngine.Result result = engine.apply(raced, 0, new CallOneCard(raceId), NOW + 900);

        assertThat(result.newState().race()).isNull();
        assertThat(result.newState().hands().get(0)).hasSize(1);
        assertThat(result.events()).containsExactly(
                new RaceResolved(raceId, RaceOutcome.CALLED, 0),
                new TurnChanged(1, 1, 0));
    }

    @Test
    void being_caught_costs_one_card_without_ending_the_turn_or_counting_as_one() {
        OneCardEngine engine = humans(3);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW).newState();
        int raceId = raced.race().raceId();

        OneCardEngine.Result result = engine.apply(raced, 2, new Catch(raceId), NOW + 700);

        OneCardState state = result.newState();
        assertThat(state.hands().get(0)).hasSize(2);
        assertThat(state.turnCount()).isEqualTo(raced.turnCount());
        assertThat(state.passStreak()).isEqualTo(raced.passStreak());
        assertThat(result.events().getFirst()).isEqualTo(new RaceResolved(raceId, RaceOutcome.CAUGHT, 2));
        assertThat(result.events()).contains(new CardsDrawn(0, 1, DrawReason.PENALTY, 2, state.drawPile().size()));
        assertThat(result.events().getLast()).isEqualTo(new TurnChanged(1, 1, 0));
        OneCardInvariantChecker.check(state);
    }

    @Test
    void a_king_still_gives_the_owner_another_turn_after_being_caught() {
        OneCardEngine engine = humans(3);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(PlayingCard.KING)), 0,
                PlayCard.of(heart(PlayingCard.KING)), NOW).newState();

        OneCardState after = engine.apply(raced, 1, new Catch(raced.race().raceId()), NOW + 500).newState();

        assertThat(after.turnSeat()).isZero();
        assertThat(after.hands().get(0)).hasSize(2);
    }

    @Test
    void a_jack_still_skips_and_an_attack_still_lands_after_the_race() {
        OneCardEngine engine = humans(3);
        OneCardState jack = engine.apply(aboutToGoDownToOne(heart(PlayingCard.JACK)), 0,
                PlayCard.of(heart(PlayingCard.JACK)), NOW).newState();
        OneCardState attack = engine.apply(aboutToGoDownToOne(heart(2)), 0, PlayCard.of(heart(2)), NOW).newState();

        assertThat(engine.apply(jack, 0, new CallOneCard(jack.race().raceId()), NOW).newState().turnSeat())
                .isEqualTo(2);
        OneCardEngine.Result attacked = engine.apply(attack, 0, new CallOneCard(attack.race().raceId()), NOW);
        assertThat(attacked.events().getLast()).isEqualTo(new TurnChanged(1, 1, 2));
        assertThat(attacked.newState().attackStack()).isEqualTo(2);
    }

    @Test
    void presses_are_checked_against_the_window() {
        OneCardEngine engine = humans(3);
        OneCardState calm = aboutToGoDownToOne(heart(9));
        OneCardState raced = engine.apply(calm, 0, PlayCard.of(heart(9)), NOW).newState();
        int raceId = raced.race().raceId();

        assertThat(rejection(engine, calm, 1, new Catch(0))).isEqualTo(RejectionReason.NO_RACE);
        assertThat(rejection(engine, raced, 1, new Catch(raceId + 1))).isEqualTo(RejectionReason.NO_RACE);
        assertThat(rejection(engine, raced, 1, new CallOneCard(raceId))).isEqualTo(RejectionReason.NOT_RACE_OWNER);
        assertThat(rejection(engine, raced, 0, new Catch(raceId))).isEqualTo(RejectionReason.OWNER_CANNOT_CATCH);
        assertThat(rejection(engine, raced, 1, PlayCard.of(spade(4)))).isEqualTo(RejectionReason.RACE_IN_PROGRESS);
        assertThat(rejection(engine, raced, 1, new Draw())).isEqualTo(RejectionReason.RACE_IN_PROGRESS);
    }

    @Test
    void during_a_race_the_owner_may_call_and_everyone_else_may_catch() {
        OneCardEngine engine = humans(3);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW).newState();
        int raceId = raced.race().raceId();

        assertThat(engine.legalActions(raced, 0)).containsExactly(new CallOneCard(raceId));
        assertThat(engine.legalActions(raced, 2)).containsExactly(new Catch(raceId));
    }

    @Test
    void without_bots_the_window_expires_after_its_length() {
        OneCardEngine engine = humans(3);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW).newState();

        assertThat(engine.timerDeadline(raced)).hasValue(NOW + 3_000);
        OneCardEngine.Result expired = engine.onTimer(raced).orElseThrow();
        assertThat(expired.events()).containsExactly(
                new RaceResolved(raced.race().raceId(), RaceOutcome.EXPIRED, -1),
                new TurnChanged(1, 1, 0));
    }

    @Test
    void a_bot_owner_calls_after_its_reaction_time() {
        OneCardEngine engine = engine(3, FIXED, 0, 2);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW).newState();

        assertThat(engine.timerDeadline(raced)).hasValue(NOW + 1_200);
        assertThat(engine.onTimer(raced).orElseThrow().events().getFirst())
                .isEqualTo(new RaceResolved(raced.race().raceId(), RaceOutcome.CALLED, 0));
    }

    @Test
    void a_bot_catches_a_human_owner_who_is_too_slow() {
        OneCardEngine engine = engine(3, FIXED, 2);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW).newState();

        assertThat(engine.timerDeadline(raced)).hasValue(NOW + 1_500);
        OneCardEngine.Result caught = engine.onTimer(raced).orElseThrow();
        assertThat(caught.events().getFirst())
                .isEqualTo(new RaceResolved(raced.race().raceId(), RaceOutcome.CAUGHT, 2));
        assertThat(caught.newState().hands().get(0)).hasSize(2);
    }

    @Test
    void a_bot_slower_than_the_window_never_presses() {
        OneCardEngine engine = engine(3, new RaceSettings(3_000, 1_000, 1_000, 3_500, 3_500, 8), 2);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW).newState();

        assertThat(raced.race().botPress()).isNull();
        assertThat(engine.timerDeadline(raced)).hasValue(NOW + 3_000);
    }

    @Test
    void there_is_no_timer_outside_a_race() {
        OneCardEngine engine = humans(3);
        OneCardState calm = aboutToGoDownToOne(heart(9));

        assertThat(engine.timerDeadline(calm)).isEmpty();
        assertThat(engine.onTimer(calm)).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: FAIL — `compileTestJava` 에서 `cannot find symbol`(`RaceSettings`·`timerDeadline`·`onTimer` 가 아직 없다).

- [ ] **Step 3: 구현 — 설정**

`server/src/main/java/com/mirboard/domain/game/onecard/RaceSettings.java`:

```java
package com.mirboard.domain.game.onecard;

/**
 * 외치기 경쟁의 시간 설정 (`docs/rules-onecard.md` §9). 운영값은 S3 에서 설정으로 주입한다.
 *
 * @param windowMillis     창 길이
 * @param ownerMinMillis   1장 남은 봇이 "원카드!" 를 누르는 반응 시간 하한
 * @param ownerMaxMillis   〃 상한(포함)
 * @param catcherMinMillis 다른 봇이 "잡기!" 를 누르는 반응 시간 하한
 * @param catcherMaxMillis 〃 상한(포함)
 * @param slotCount        버튼 위치 슬롯 수 — 클라와 맞춘 프로토콜 상수
 */
public record RaceSettings(long windowMillis,
                           long ownerMinMillis,
                           long ownerMaxMillis,
                           long catcherMinMillis,
                           long catcherMaxMillis,
                           int slotCount) {

    /** 설계서 기본값 — 창 3초, 봇 반응 1.0~2.5초, 슬롯 8개. */
    public static final RaceSettings DEFAULT = new RaceSettings(3_000, 1_000, 2_500, 1_000, 2_500, 8);

    public RaceSettings {
        if (windowMillis <= 0 || slotCount < 1
                || ownerMinMillis < 0 || ownerMinMillis > ownerMaxMillis
                || catcherMinMillis < 0 || catcherMinMillis > catcherMaxMillis) {
            throw new IllegalArgumentException("invalid race settings");
        }
    }
}
```

- [ ] **Step 4: 구현 — 엔진에 경쟁 창 추가** (`server/src/main/java/com/mirboard/domain/game/onecard/OneCardEngine.java`)

```diff
 import com.mirboard.domain.game.onecard.card.Suit;
 import com.mirboard.domain.game.onecard.event.OneCardEvent;
 import com.mirboard.domain.game.onecard.event.OneCardEvent.DrawReason;
+import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceOutcome;
 import com.mirboard.domain.game.onecard.rules.PlayRules;
 import com.mirboard.domain.game.onecard.rules.Ranking;
 import com.mirboard.domain.game.onecard.rules.TurnOrder;
```

```diff
 import com.mirboard.domain.game.onecard.state.RaceWindow;
 import java.util.ArrayList;
 import java.util.List;
+import java.util.Optional;
+import java.util.OptionalLong;
 import java.util.Random;
 
 /**
```

```diff
     /** 총 차례 상한 (§11.3). */
     public static final int TURN_LIMIT = 600;
 
+    /** 경쟁 버튼 지터 범위 — −100~100 (§9, 설계서 §4.4). */
+    public static final int JITTER_RANGE = 100;
+
     private final GameContext context;
     private final Random rng;
+    private final RaceSettings settings;
 
-    public OneCardEngine(GameContext context, Random rng) {
+    public OneCardEngine(GameContext context, Random rng, RaceSettings settings) {
         this.context = context;
         this.rng = rng;
+        this.settings = settings;
     }
 
+    public OneCardEngine(GameContext context, Random rng) {
+        this(context, rng, RaceSettings.DEFAULT);
+    }
+
     public GameContext context() {
         return context;
     }
```

```diff
         return switch (action) {
             case OneCardAction.PlayCard play -> play(state, seat, play, now);
             case OneCardAction.Draw __ -> draw(state, seat);
-            // 경쟁 창은 다음 태스크에서 연다 — 지금은 열린 창이 있을 수 없다.
-            case OneCardAction.CallOneCard __ -> throw rejected(RejectionReason.NO_RACE);
-            case OneCardAction.Catch __ -> throw rejected(RejectionReason.NO_RACE);
+            case OneCardAction.CallOneCard call -> press(state, seat, call.raceId(), true);
+            case OneCardAction.Catch katch -> press(state, seat, katch.raceId(), false);
         };
     }
 
```

```diff
         } else if (t.turnCount >= TURN_LIMIT) {
             finish(t, EndReason.STALEMATE, -1, events);
         } else {
-            passTurn(t, TurnOrder.afterPlay(t.seatCount(), t::alive, seat, t.direction, card), events);
+            int next = TurnOrder.afterPlay(t.seatCount(), t::alive, seat, t.direction, card);
+            if (hand.size() == 1) {
+                openRace(t, seat, next, now, events);
+            } else {
+                passTurn(t, next, events);
+            }
         }
         return new Result(t.freeze(), events);
     }
```

```diff
         }
         if (state.turnSeat() != seat) {
             throw rejected(RejectionReason.NOT_YOUR_TURN);
+        }
+    }
+
+    // ---------- 외치기 경쟁 (§9) ----------
+
+    private Result press(OneCardState state, int seat, int raceId, boolean call) {
+        RaceWindow race = state.race();
+        if (race == null || race.raceId() != raceId) {
+            throw rejected(RejectionReason.NO_RACE);
+        }
+        if (call && seat != race.ownerSeat()) {
+            throw rejected(RejectionReason.NOT_RACE_OWNER);
+        }
+        if (!call && seat == race.ownerSeat()) {
+            throw rejected(RejectionReason.OWNER_CANNOT_CATCH);
+        }
+        return resolveRace(state, call ? RaceOutcome.CALLED : RaceOutcome.CAUGHT, seat);
+    }
+
+    /**
+     * 경쟁 창이 저절로 닫히는 시각(epoch ms) — 가장 빠른 봇의 누름 또는 창 길이. 창이 없으면 비어 있다.
+     * 포트의 {@code timer} 는 이 값에서 지금 시각을 빼 남은 시간을 만든다(S3).
+     */
+    public OptionalLong timerDeadline(OneCardState state) {
+        if (state.ended() || state.race() == null) {
+            return OptionalLong.empty();
+        }
+        return OptionalLong.of(state.race().deadline());
+    }
+
+    /**
+     * 경쟁 창의 시간 전이 — 추첨해 둔 봇의 누름이 창보다 빠르면 그 누름, 아니면 아무도 안 누른 채 닫힘(§9-4).
+     * 발화를 믿는다: 시각을 다시 보지 않는다(설계서 §4.5).
+     */
+    public Optional<Result> onTimer(OneCardState state) {
+        if (state.ended() || state.race() == null) {
+            return Optional.empty();
         }
+        RaceWindow.BotPress bot = state.race().botPress();
+        if (bot == null) {
+            return Optional.of(resolveRace(state, RaceOutcome.EXPIRED, -1));
+        }
+        return Optional.of(resolveRace(state, bot.call() ? RaceOutcome.CALLED : RaceOutcome.CAUGHT, bot.seat()));
     }
 
+    private void openRace(Table t, int owner, int next, long now, List<OneCardEvent> events) {
+        int raceId = t.version;
+        int slot = rng.nextInt(settings.slotCount());
+        int jitterX = rng.nextInt(2 * JITTER_RANGE + 1) - JITTER_RANGE;
+        int jitterY = rng.nextInt(2 * JITTER_RANGE + 1) - JITTER_RANGE;
+        t.race = new RaceWindow(raceId, owner, slot, jitterX, jitterY, now, settings.windowMillis(), next,
+                fastestBot(t, owner));
+        t.turnSeat = -1;
+        events.add(new OneCardEvent.RaceOpened(raceId, owner, slot, jitterX, jitterY, settings.windowMillis()));
+    }
+
+    /** §9-6 — 살아 있는 봇마다 반응 시간을 뽑아 가장 빠른 한 명만 남긴다. 창보다 늦으면 없음. */
+    private RaceWindow.BotPress fastestBot(Table t, int owner) {
+        RaceWindow.BotPress fastest = null;
+        for (int seat = 0; seat < t.seatCount(); seat++) {
+            if (!t.alive(seat) || !context.botSeats().contains(seat)) {
+                continue;
+            }
+            boolean call = seat == owner;
+            long delay = call
+                    ? rng.nextLong(settings.ownerMinMillis(), settings.ownerMaxMillis() + 1)
+                    : rng.nextLong(settings.catcherMinMillis(), settings.catcherMaxMillis() + 1);
+            if (fastest == null || delay < fastest.delayMillis()) {
+                fastest = new RaceWindow.BotPress(seat, call, delay);
+            }
+        }
+        return fastest != null && fastest.delayMillis() < settings.windowMillis() ? fastest : null;
+    }
+
+    private Result resolveRace(OneCardState state, RaceOutcome outcome, int bySeat) {
+        Table t = new Table(state);
+        RaceWindow race = t.race;
+        t.race = null;
+        t.version++;
+        List<OneCardEvent> events = new ArrayList<>();
+        events.add(new OneCardEvent.RaceResolved(race.raceId(), outcome, bySeat));
+        if (outcome == RaceOutcome.CAUGHT) {
+            // §9.1 — 벌칙은 차례를 끝내지 않고 공격 누적·차례 수·연속 패스 수를 바꾸지 않는다.
+            int owner = race.ownerSeat();
+            List<PlayingCard> drawn = drawFromPile(t, 1, events);
+            List<PlayingCard> hand = t.hands.get(owner);
+            hand.addAll(drawn);
+            events.add(new OneCardEvent.CardsDrawn(owner, drawn.size(), DrawReason.PENALTY, hand.size(),
+                    t.drawPile.size()));
+            events.add(new OneCardEvent.HandUpdated(owner, hand, drawn, t.version));
+        }
+        passTurn(t, race.nextSeat(), events);
+        return new Result(t.freeze(), events);
+    }
+
     // ---------- 진행 질의 ----------
 
     /** 지금 행동을 기다리는 좌석. 경쟁 창이 열렸거나 끝났으면 비어 있다(설계서 §4.5). */
```

```diff
         if (state.ended() || seat < 0 || seat >= state.seatCount() || !state.alive(seat)) {
             return List.of();
         }
+        RaceWindow race = state.race();
+        if (race != null) {
+            return List.of(seat == race.ownerSeat()
+                    ? new OneCardAction.CallOneCard(race.raceId())
+                    : new OneCardAction.Catch(race.raceId()));
+        }
         if (state.turnSeat() != seat) {
             return List.of();
         }
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: PASS — 원카드 테스트 누적 78건(경쟁 창 12건). Task 5 의 엔진 테스트 21건도 그대로 통과.

- [ ] **Step 6: 커밋**

```bash
git add server/src/main/java/com/mirboard/domain/game/onecard/RaceSettings.java \
  server/src/main/java/com/mirboard/domain/game/onecard/OneCardEngine.java \
  server/src/test/java/com/mirboard/domain/game/onecard/OneCardEngineRaceTest.java
git commit -m "feat(D-127): 원카드 엔진 — 외치기 경쟁 창·봇 반응·벌칙

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 7: 엔진 — 탈주 (§10, §9.2)

**Files:**
- Modify: `server/src/main/java/com/mirboard/domain/game/onecard/OneCardEngine.java`
- Create: `server/src/test/java/com/mirboard/domain/game/onecard/OneCardEngineDesertionTest.java`

**Interfaces:**
- Consumes: Task 6 엔진.
- Produces: `record Desertion(OneCardState newState, Outcome outcome, List<OneCardEvent> events)` +
  `enum Outcome { NOT_APPLICABLE, CONTINUED, MATCH_ENDED }`, `Desertion desert(OneCardState state, int seat)` —
  S3 어댑터가 포트의 `DesertOutcome` 으로 옮긴다(`NOT_APPLICABLE`→`NOT_APPLICABLE`, `CONTINUED`→`MATCH_CONTINUES`,
  `MATCH_ENDED`→`MATCH_ENDED`).

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardEngineDesertionTest.java`:

```java
package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.OneCardEngine.Desertion;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.event.OneCardEvent.HandUpdated;
import com.mirboard.domain.game.onecard.event.OneCardEvent.PlayerEliminated;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceOutcome;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceResolved;
import com.mirboard.domain.game.onecard.event.OneCardEvent.TurnChanged;
import com.mirboard.domain.game.onecard.invariant.OneCardInvariantChecker;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.MatchResult.SeatStatus;
import com.mirboard.domain.game.onecard.state.MatchResult.Standing;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** `docs/rules-onecard.md` §10, §9.2, §11 — 탈주. */
class OneCardEngineDesertionTest {

    private static final long NOW = 5_000L;

    private static OneCardEngine engine(int seats, Integer... botSeats) {
        List<Long> ids = LongStream.range(0, seats).map(i -> 100 + i).boxed().toList();
        return new OneCardEngine(new GameContext("r", ids, 0, 0, Arrays.asList(botSeats)), new Random(3));
    }

    private static OneCardState fourSeats(int turn) {
        return seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)), hand(club(9), club(10), club(11)))
                .top(heart(5)).turn(turn).build();
    }

    @Test
    void an_eliminated_seat_or_a_finished_match_is_not_a_desertion() {
        OneCardEngine engine = engine(3);
        OneCardState withOut = seats(hand(heart(9), club(3)), hand(), hand(diamond(4), diamond(6)))
                .eliminated(1, Elimination.Reason.BANKRUPT, 20).top(heart(5)).turn(0).build();
        OneCardState ended = engine.apply(seats(hand(heart(2)), hand(spade(4)), hand(club(9)))
                .top(heart(5)).turn(0).build(), 0, PlayCard.of(heart(2)), NOW).newState();

        Desertion bankruptLeaves = engine.desert(withOut, 1);
        Desertion afterEnd = engine.desert(ended, 2);

        assertThat(bankruptLeaves.outcome()).isEqualTo(Desertion.Outcome.NOT_APPLICABLE);
        assertThat(bankruptLeaves.newState()).isSameAs(withOut);
        assertThat(bankruptLeaves.events()).isEmpty();
        assertThat(afterEnd.outcome()).isEqualTo(Desertion.Outcome.NOT_APPLICABLE);
    }

    @Test
    void a_deserter_on_turn_drops_the_attack_and_the_next_live_seat_plays_freely() {
        OneCardState attacked = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(2)).attack(2).turn(1).build();

        Desertion result = engine(3).desert(attacked, 1);

        OneCardState state = result.newState();
        assertThat(result.outcome()).isEqualTo(Desertion.Outcome.CONTINUED);
        assertThat(state.attackStack()).isZero();
        assertThat(state.turnSeat()).isEqualTo(2);
        assertThat(state.hands().get(1)).isEmpty();
        assertThat(state.drawPile()).endsWith(spade(4), spade(6), spade(8));
        assertThat(result.events()).containsExactly(
                new PlayerEliminated(1, Elimination.Reason.DESERTED, 3),
                new HandUpdated(1, List.of(), List.of(), state.version()),
                new TurnChanged(2, 1, 0));
        OneCardInvariantChecker.check(state);
    }

    @Test
    void someone_else_deserting_keeps_the_current_turn() {
        Desertion result = engine(4).desert(fourSeats(0), 2);

        assertThat(result.newState().turnSeat()).isZero();
        assertThat(result.events()).noneMatch(TurnChanged.class::isInstance);
    }

    @Test
    void a_desertion_resets_the_pass_streak() {
        OneCardState passing = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).passStreak(2).turn(0).build();

        assertThat(engine(3).desert(passing, 2).newState().passStreak()).isZero();
    }

    @Test
    void during_a_race_the_window_closes_without_penalty_and_the_reserved_seat_plays() {
        OneCardEngine engine = engine(4);
        OneCardState raced = engine.apply(seats(hand(heart(2), club(3)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)), hand(club(9), club(10), club(11)))
                .top(heart(5)).turn(0).build(), 0, PlayCard.of(heart(2)), NOW).newState();
        int raceId = raced.race().raceId();

        Desertion result = engine.desert(raced, 3);

        assertThat(result.events().getFirst()).isEqualTo(new RaceResolved(raceId, RaceOutcome.CANCELLED, -1));
        assertThat(result.events().getLast()).isEqualTo(new TurnChanged(1, 1, 2));
        assertThat(result.newState().hands().get(0)).hasSize(1);
        OneCardInvariantChecker.check(result.newState());
    }

    @Test
    void if_the_reserved_seat_deserts_during_a_race_the_next_seat_plays_without_the_attack() {
        OneCardEngine engine = engine(4);
        OneCardState raced = engine.apply(seats(hand(heart(2), club(3)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)), hand(club(9), club(10), club(11)))
                .top(heart(5)).turn(0).build(), 0, PlayCard.of(heart(2)), NOW).newState();

        Desertion result = engine.desert(raced, 1);

        assertThat(result.newState().attackStack()).isZero();
        assertThat(result.events().getLast()).isEqualTo(new TurnChanged(2, 1, 0));
    }

    @Test
    void if_a_king_owner_deserts_during_the_race_the_next_live_seat_plays() {
        OneCardEngine engine = engine(4);
        OneCardState raced = engine.apply(seats(hand(heart(PlayingCard.KING), club(3)),
                hand(spade(4), spade(6), spade(8)), hand(diamond(4), diamond(6), diamond(8)),
                hand(club(9), club(10), club(11)))
                .top(heart(5)).turn(0).build(), 0, PlayCard.of(heart(PlayingCard.KING)), NOW).newState();

        Desertion result = engine.desert(raced, 0);

        assertThat(result.newState().turnSeat()).isEqualTo(1);
        assertThat(result.newState().race()).isNull();
    }

    @Test
    void the_last_seat_standing_wins_and_the_deserter_ranks_last() {
        OneCardState twoSeats = seats(hand(heart(9), club(3)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();

        Desertion result = engine(2).desert(twoSeats, 1);

        assertThat(result.outcome()).isEqualTo(Desertion.Outcome.MATCH_ENDED);
        assertThat(result.newState().result().reason()).isEqualTo(EndReason.LAST_STANDING);
        assertThat(result.newState().result().standings()).containsExactly(
                new Standing(0, 1, 2, SeatStatus.ALIVE),
                new Standing(1, 2, 3, SeatStatus.DESERTED));
    }

    @Test
    void when_no_human_is_left_alive_the_match_ends() {
        Desertion result = engine(4, 1, 2, 3).desert(fourSeats(1), 0);

        assertThat(result.outcome()).isEqualTo(Desertion.Outcome.MATCH_ENDED);
        assertThat(result.newState().result().reason()).isEqualTo(EndReason.NO_HUMANS);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: FAIL — `compileTestJava` 에서 `cannot find symbol`(`Desertion`·`desert` 가 아직 없다).

- [ ] **Step 3: 구현 — 엔진에 탈주 추가** (`server/src/main/java/com/mirboard/domain/game/onecard/OneCardEngine.java`)

```diff
         }
     }
 
+    /** {@link #desert} 결과. */
+    public record Desertion(OneCardState newState, Outcome outcome, List<OneCardEvent> events) {
+
+        public enum Outcome {
+            /** 이미 끝났거나 이미 탈락한 좌석 — 상태 무변경, 이벤트 0건 (§10). */
+            NOT_APPLICABLE,
+            /** 남은 사람끼리 계속. */
+            CONTINUED,
+            /** 탈락으로 1명만 남았거나 살아 있는 사람이 없어 끝났다 (§11.1). */
+            MATCH_ENDED
+        }
+
+        public Desertion {
+            events = List.copyOf(events);
+        }
+    }
+
     // ---------- 시작 (§2, §3) ----------
 
     public Result startMatch() {
```

```diff
         return new Result(t.freeze(), events);
     }
 
+    // ---------- 탈주 (§10, §9.2) ----------
+
+    /**
+     * 좌석 탈주 — 파산과 같은 탈락 경로. 이미 끝났거나 이미 탈락한 좌석이면 {@code NOT_APPLICABLE}(§10).
+     * 경쟁 창이 열려 있으면 벌칙 없이 닫고(§9-7), 정해 둔 다음 차례로 넘기되 그 사람이 탈주자면 그다음 사람이
+     * 공격 없이 받는다(§9.2). 창이 없고 탈주자의 차례였다면 걸린 공격은 사라진다(§10).
+     */
+    public Desertion desert(OneCardState state, int seat) {
+        if (state.ended() || seat < 0 || seat >= state.seatCount() || !state.alive(seat)) {
+            return new Desertion(state, Desertion.Outcome.NOT_APPLICABLE, List.of());
+        }
+        Table t = new Table(state);
+        t.version++;
+        List<OneCardEvent> events = new ArrayList<>();
+        RaceWindow race = t.race;
+        if (race != null) {
+            t.race = null;
+            events.add(new OneCardEvent.RaceResolved(race.raceId(), RaceOutcome.CANCELLED, -1));
+        }
+        boolean deserterHadTurn = race == null && t.turnSeat == seat;
+        eliminate(t, seat, Elimination.Reason.DESERTED, events);
+        if (endIfDecided(t, events)) {
+            return new Desertion(t.freeze(), Desertion.Outcome.MATCH_ENDED, events);
+        }
+        if (race != null) {
+            int next = race.nextSeat();
+            if (!t.alive(next)) {
+                next = TurnOrder.nextAlive(t.seatCount(), t::alive, next, t.direction);
+                t.attackStack = 0;
+            }
+            passTurn(t, next, events);
+        } else if (deserterHadTurn) {
+            t.attackStack = 0;
+            passTurn(t, TurnOrder.nextAlive(t.seatCount(), t::alive, seat, t.direction), events);
+        }
+        return new Desertion(t.freeze(), Desertion.Outcome.CONTINUED, events);
+    }
+
     // ---------- 진행 질의 ----------
 
     /** 지금 행동을 기다리는 좌석. 경쟁 창이 열렸거나 끝났으면 비어 있다(설계서 §4.5). */
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: PASS — 원카드 테스트 누적 87건(탈주 9건).

- [ ] **Step 5: 커밋**

```bash
git add server/src/main/java/com/mirboard/domain/game/onecard/OneCardEngine.java \
  server/src/test/java/com/mirboard/domain/game/onecard/OneCardEngineDesertionTest.java
git commit -m "feat(D-127): 원카드 엔진 — 탈주(창 중 탈주 포함)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 8: 2~6인 무작위 시뮬레이션 (§14)

이 테스트는 이미 만든 엔진을 대규모로 검증한다 — 그래서 처음부터 통과하는 것이 정상이다. 대신 Step 3 에서 고의
결함을 넣어 **실제로 실패하는지**(검출력)를 확인하고 되돌린다.

**Files:**
- Create: `server/src/test/java/com/mirboard/domain/game/onecard/OneCardMatchSimulationTest.java`

**Interfaces:**
- Consumes: Task 5~7 엔진 공개 API(`startMatch`·`apply`·`legalActions`·`onTimer`·`desert`), `OneCardInvariantChecker`.

- [ ] **Step 1: 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardMatchSimulationTest.java`:

```java
package com.mirboard.domain.game.onecard;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.invariant.OneCardInvariantChecker;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * `docs/rules-onecard.md` §14 — 무작위 합법수로 판을 끝까지 돌리며 매 전이 뒤 불변식을 검사한다. 경쟁 창은
 * 무작위로 누르거나 시간을 흘려 닫고, 가끔 탈주를 끼워 넣는다.
 */
class OneCardMatchSimulationTest {

    private static final int GAMES_PER_SEAT_COUNT = 2_000;
    private static final int BOT_GAMES_PER_SEAT_COUNT = 300;
    /** 차례 상한(600) + 경쟁 창·탈주로 늘어나는 전이. 넘으면 끝나지 않는 판이다. */
    private static final int STEP_GUARD = 3 * OneCardEngine.TURN_LIMIT;

    private record Outcome(EndReason reason, boolean hitTurnLimit) {
    }

    private static Outcome play(int seats, long seed, Set<Integer> bots) {
        Random rng = new Random(seed);
        List<Long> ids = LongStream.range(0, seats).map(i -> 100 + i).boxed().toList();
        OneCardEngine engine = new OneCardEngine(
                new GameContext("sim", ids, 0, 0, new ArrayList<>(bots)), new Random(seed * 31 + 7));
        OneCardState state = engine.startMatch().newState();
        OneCardInvariantChecker.check(state);
        long now = 0;
        for (int step = 0; step < STEP_GUARD; step++) {
            if (state.ended()) {
                return new Outcome(state.result().reason(), state.turnCount() >= OneCardEngine.TURN_LIMIT);
            }
            now += 400;
            if (rng.nextInt(400) == 0) {
                List<Integer> alive = state.aliveSeats();
                state = engine.desert(state, alive.get(rng.nextInt(alive.size()))).newState();
            } else if (state.race() != null) {
                state = stepRace(engine, state, rng, bots, now);
            } else {
                int seat = state.turnSeat();
                state = engine.apply(state, seat, choose(engine.legalActions(state, seat), rng), now).newState();
            }
            OneCardInvariantChecker.check(state);
        }
        throw new AssertionError("match did not end within " + STEP_GUARD + " steps (seed " + seed + ")");
    }

    /** 경쟁 창 — 3분의 1은 시간이 흘러 닫히고(봇 누름 또는 만료), 나머지는 살아 있는 사람이 누른다. */
    private static OneCardState stepRace(OneCardEngine engine, OneCardState state, Random rng, Set<Integer> bots,
                                         long now) {
        List<Integer> humans = state.aliveSeats().stream().filter(seat -> !bots.contains(seat)).toList();
        if (humans.isEmpty() || rng.nextInt(3) == 0) {
            return engine.onTimer(state).orElseThrow().newState();
        }
        int presser = humans.get(rng.nextInt(humans.size()));
        return engine.apply(state, presser, engine.legalActions(state, presser).getFirst(), now).newState();
    }

    /** 낼 수 있으면 열에 아홉은 무작위 카드를 낸다 — 균등 추첨이면 먹기가 너무 잦아 판이 늘어진다. */
    private static OneCardAction choose(List<OneCardAction> legal, Random rng) {
        List<OneCardAction> plays = legal.stream().filter(OneCardAction.PlayCard.class::isInstance).toList();
        if (!plays.isEmpty() && rng.nextInt(10) < 9) {
            return plays.get(rng.nextInt(plays.size()));
        }
        return new OneCardAction.Draw();
    }

    @ParameterizedTest(name = "{0}인 × " + GAMES_PER_SEAT_COUNT + "판")
    @ValueSource(ints = {2, 3, 4, 5, 6})
    void every_match_ends_with_the_cards_conserved(int seats) {
        Map<EndReason, Integer> reasons = new EnumMap<>(EndReason.class);
        int turnLimitHits = 0;
        for (int game = 0; game < GAMES_PER_SEAT_COUNT; game++) {
            Outcome outcome = play(seats, seats * 1_000_003L + game, Set.of());
            reasons.merge(outcome.reason(), 1, Integer::sum);
            if (outcome.hitTurnLimit()) {
                turnLimitHits++;
            }
        }

        assertThat(reasons.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(GAMES_PER_SEAT_COUNT);
        assertThat(reasons).containsKey(EndReason.FINISHED);
        // 차례 상한은 안전장치다 — 전략 없는 무작위 판에서도 열에 아홉은 그 전에 끝나야 한다(§11.3, §14).
        // 측정값(시드 고정): 2인 1% 미만 · 3인 1.0% · 4인 2.1% · 5인 2.3% · 6인 5.1%.
        assertThat(turnLimitHits).as("차례 상한 도달 %d판 / %s", turnLimitHits, reasons)
                .isLessThan(GAMES_PER_SEAT_COUNT / 10);
    }

    /** 봇 좌석은 경쟁 창에서 엔진 타이머({@code onTimer})의 봇 누름으로만 참여한다. */
    @ParameterizedTest(name = "{0}인(사람 1 + 봇) × " + BOT_GAMES_PER_SEAT_COUNT + "판")
    @ValueSource(ints = {2, 3, 4, 5, 6})
    void matches_with_one_human_and_bots_always_end(int seats) {
        Set<Integer> bots = IntStream.range(1, seats).boxed().collect(Collectors.toSet());
        Map<EndReason, Integer> reasons = new EnumMap<>(EndReason.class);
        for (int game = 0; game < BOT_GAMES_PER_SEAT_COUNT; game++) {
            reasons.merge(play(seats, seats * 7_919L + game, bots).reason(), 1, Integer::sum);
        }

        assertThat(reasons.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(BOT_GAMES_PER_SEAT_COUNT);
        if (seats >= 3) {
            // 사람이 탈주·파산해 봇만 남으면 그 자리에서 끝난다(§11.1 NO_HUMANS). 2인은 1명만 남아 LAST_STANDING.
            assertThat(reasons).as("%s", reasons).containsKey(EndReason.NO_HUMANS);
        }
    }
}
```

- [ ] **Step 2: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: PASS — 원카드 테스트 누적 97건(시뮬레이션 10건, 약 5초).

- [ ] **Step 3: 검출력 확인 — 고의 결함을 넣고 실패를 본 뒤 되돌린다**

`server/src/main/java/com/mirboard/domain/game/onecard/OneCardEngine.java` 의 `eliminate` 에서 손패를 더미로 옮기는 줄을 잠시 지운다:

```diff
         List<PlayingCard> hand = t.hands.get(seat);
         int held = hand.size();
-        t.drawPile.addAll(hand);
         hand.clear();
```

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.OneCardMatchSimulationTest"`
Expected: FAIL — `10 tests completed, 10 failed`(탈락이 나는 판에서 54장 보존 불변식이 깨진다).

그 줄을 원래대로 되돌리고 `git diff -- server/src/main/java/com/mirboard/domain/game/onecard/OneCardEngine.java` 출력이 비었는지 확인한다.

- [ ] **Step 4: 되돌린 뒤 전체 통과**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"`
Expected: PASS — 원카드 테스트 97건.

- [ ] **Step 5: 커밋**

```bash
git add server/src/test/java/com/mirboard/domain/game/onecard/OneCardMatchSimulationTest.java
git commit -m "test(D-127): 원카드 2~6인 무작위 시뮬레이션

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 9: 문서 정합 — 룰↔코드 매핑·설계서 동기화·명령·수치 + Phase Gate

코드는 바꾸지 않는다. 아래 diff 는 S2 착수 시점 main(`62dd632`) 기준이다. 문맥 줄이 맞지 않으면(그사이 main 이
바뀌어 브랜치를 맞췄다면) 같은 뜻으로 현재 문장에 적용하고, 수치는 Step 5 규칙을 따른다.

**Files:**
- Modify: `docs/rules-onecard.md` — 머리말, §1 표의 조커 코드 표기, §1~§11 끝 **코드:**/**테스트:**(/**갭:**), §14 결과
- Modify: `docs/plans/onecard.md` — §4.1 패키지, §4.2 상태, §4.3 이벤트 표, §4.4 벌칙·지터, §4.5b, §7(D-126 관계)
- Modify: `scripts/check.sh`, `CLAUDE.md` — `rules` 묶음과 테스트 명령, 결정 이력 번호
- Modify: `docs/implementation-status.md`, `README.md`, `docs/case-study-multi-game.md`, `docs/plans/mvp-roadmap.md`

**Interfaces:**
- Consumes: Task 1~8 의 클래스·메서드·테스트 이름(매핑에 그대로 인용한다).

- [ ] **Step 1: 룰 명세에 코드·테스트 매핑** — `docs/rules-onecard.md`

```diff
 # 원카드 룰 명세 (One Card Rules)
 
-본 문서는 Mirboard 원카드 룰의 **단일 진실 공급원**이다. 코드(S2 순수 엔진)는 이 문서를 따르고, 어긋나면
-코드를 고치거나 이 문서와 결정(`docs/decisions.md`)을 함께 고친다. 코드 위치·테스트 매핑은 S2 에서 각 절에
-`rules-skullking.md` 와 같은 표기(**코드:** / **테스트:** / **갭:**)로 붙인다.
+본 문서는 Mirboard 원카드 룰의 **단일 진실 공급원**이다. 코드(S2 순수 엔진, D-127)는 이 문서를 따르고,
+어긋나면 코드를 고치거나 이 문서와 결정(`docs/decisions.md`)을 함께 고친다. 각 절 끝에 `rules-skullking.md` 와
+같은 표기(**코드:** / **테스트:** / **갭:**)로 코드 위치와 테스트를 붙였다. 경로는
+`server/src/{main,test}/java/com/mirboard/domain/game/onecard/` 기준이고, 위치는 줄 번호 대신 메서드 이름으로
+적는다(줄 번호는 코드가 바뀌면 바로 틀린다). 포트 어댑터·저장·봇 정책·기록은 S3 범위다.
 
 > **출처와 하우스 룰.** 원카드는 모임마다 규칙이 크게 다르다. 이 문서는 D-123 에서 고른 **표준 기본형**만
```

```diff
 | --- | --- | --- |
 | 무늬 카드 | 52 | 4무늬(♠ `SPADE` · ♥ `HEART` · ♦ `DIAMOND` · ♣ `CLUB`) × 13숫자 |
-| 흑백 조커 | 1 | `BLACK_JOKER` |
-| 컬러 조커 | 1 | `COLOR_JOKER` |
+| 흑백 조커 | 1 | `Joker.BLACK` |
+| 컬러 조커 | 1 | `Joker.COLOR` |
 
 숫자는 A(1) · 2~10 · J(11) · Q(12) · K(13). 조커에는 무늬·숫자가 없다.
```

```diff
 | 3·4·5·6·8·9·10 | 일반 | 없음 |
 
+- **코드:** `card/Deck.java`(`SIZE=54`, `all()`), `card/PlayingCard.java`(`isAttack`·`attackValue`·
+  `attackStrength`·`isSkip`·`isReverse`·`isExtraTurn`·`isSuitChange`·`isNormal`)
+- **테스트:** `card/PlayingCardTest`(6건) — 54장, 공격 값, 세기 순서, 특수 역할, 일반 카드, 잘못된 카드 거절
+
 ## 2. 인원과 좌석
 
```

```diff
 - **[결정] 첫 차례는 서버가 무작위로 고른다.** 방장(좌석 0)에 고정하면 선 이점이 한 사람에게 쏠린다.
 - **살아 있는 플레이어** = 탈락(§10)하지 않은 플레이어. 차례 계산과 "다음 사람"은 살아 있는 플레이어만 센다.
+
+- **코드:** 첫 차례는 `OneCardEngine.startMatch`(주입한 난수), 살아 있는 좌석은
+  `state/OneCardState.alive`·`aliveSeats`
+- **테스트:** `OneCardEnginePlayTest.the_first_seat_is_random`
 
 ## 3. 분배와 시작
```

```diff
 6인이면 42장을 나누고 시작 카드 1장을 뒤집어 뽑을 더미에 11장이 남는다.
 
+- **코드:** `Dealer.deal`(시작 카드가 일반 카드가 아니면 맨 아래로 보내고 다시 뒤집기, 더미를 다 봐도 없으면
+  처음부터 다시 나누기). 셔플은 `Dealer.Shuffler` 로 주입한다
+- **테스트:** `DealerTest`(8건) — 2~6인 분배, 맨 아래로 보내고 다시 뒤집기, 다시 나누기(§3-5), 인원 범위
+
 ## 4. 차례에 할 수 있는 것
 
```

```diff
 - **[결정] 낼 수 있어도 먹을 수 있다**(전략적 먹기).
 - 턴 제한이 있는 방에서 시간을 넘기면 먹기를 한 것으로 본다.
+
+- **코드:** `OneCardEngine.apply`(`PLAY_CARD`·`DRAW`), 시간 초과는 `OneCardEngine.timeoutAction`(먹기),
+  봇·검증용 목록은 `OneCardEngine.legalActions`
+- **테스트:** `OneCardEnginePlayTest` — `drawing_takes_one_card_even_when_a_card_could_be_played`,
+  `the_timeout_action_is_drawing_for_the_seat_on_turn_only`, `legal_actions_*`·`under_attack_*`
 
 ## 5. 내기
```

```diff
 3. 손패가 정확히 1장이면 외치기 경쟁(§9)이 먼저 열린다.
 4. 다음 차례를 정한다(§8.2).
+
+- **코드:** `rules/PlayRules.canPlay`(§5.2·§5.3), 기준 무늬 `state/OneCardState.baseSuit`, 내기 처리
+  `OneCardEngine.play`(§5.4 순서 — 효과, 0장이면 종료, 1장이면 경쟁, 다음 차례)
+- **테스트:** `rules/PlayRulesTest`(9건), `OneCardEnginePlayTest`(7 의 무늬 지정, 거절 사유)
 
 ## 6. 공격과 반격
```

```diff
 차례도 끝난다.
 
+- **코드:** 값·세기 `card/PlayingCard.attackValue`·`attackStrength`, 반격 `rules/PlayRules.canCounter`, 누적은
+  `OneCardEngine.play`, 받기는 `OneCardEngine.draw`
+- **테스트:** `rules/PlayRulesTest`(§6.2 표의 네 행),
+  `OneCardEnginePlayTest.an_attack_passes_to_the_next_seat_which_must_counter_or_draw_the_stack`
+
 ## 7. 먹기
 
```

```diff
 뽑을 더미가 비었는데 더 먹어야 하면 버린 더미의 맨 위 1장만 남기고 나머지를 섞어 뽑을 더미로 만든다. 맨 위
 카드와 그 상태(지정 무늬·공격 누적)는 그대로다.
+
+- **코드:** `OneCardEngine.draw`, 다시 채우기는 `OneCardEngine.drawFromPile`(맨 위만 남기고 섞기, 그래도
+  모자라면 있는 만큼만)
+- **테스트:** `OneCardEnginePlayTest` — `drawing_*`, `an_empty_pile_is_refilled_from_the_discard_pile_keeping_the_top`,
+  `with_nothing_left_to_draw_*`
 
 ## 8. 특수 카드와 차례 순서
```

```diff
 | (먹기) | 현재 방향의 다음 살아 있는 플레이어 |
 
+- **코드:** `rules/TurnOrder.afterPlay`(§8.2 표), `rules/TurnOrder.nextAlive`(탈락 건너뛰기), Q 의 방향
+  반전은 `OneCardEngine.play`
+- **테스트:** `rules/TurnOrderTest`(8건 — §8.2 표의 모든 행과 2인), `OneCardEnginePlayTest`(J·Q·K·2인)
+
 ## 9. 외치기 경쟁 ("원카드!" / "잡기!")
 
```

```diff
 넘어간다). 정해 둔 사람이 창 중에 탈주했다면 같은 방향으로 그다음 살아 있는 플레이어가 차례를 받고, 그 차례에
 걸려 있던 공격은 사라진다(§10 과 같은 이유).
+
+- **코드:** 창 열기 `OneCardEngine.openRace`(슬롯·지터, 봇 추첨 `fastestBot`), 누름 `OneCardEngine.press`,
+  시간 전이 `OneCardEngine.timerDeadline`·`onTimer`, 해소·벌칙 `OneCardEngine.resolveRace`, 설정 `RaceSettings`
+  (`DEFAULT` — 창 3초, 봇 1.0~2.5초, 슬롯 8), 창 상태 `state/RaceWindow`
+- **테스트:** `OneCardEngineRaceTest`(12건 — 세 결과, 벌칙 뒤 차례(§9.1), K·J·공격, 창 번호 검사, 봇 반응),
+  창 중 탈주(§9.2)는 `OneCardEngineDesertionTest`
+- **갭:** "먼저"를 가르는 서버 도착 순서·락 경합(`BUSY` 재시도)·엔진 타이머 무장은 포트 어댑터 몫이라 S3 통합
+  테스트에서 본다.
 
 ## 10. 파산과 탈락
```

```diff
   않게 한다. 공격 누적을 먹고 파산했다면 그 공격은 이미 끝났다.
 
+- **코드:** 파산은 `OneCardEngine.draw` → `eliminate`(손패를 섞지 않고 더미 맨 아래로), 탈주는
+  `OneCardEngine.desert`(이미 끝났거나 이미 탈락한 좌석이면 `NOT_APPLICABLE`)
+- **테스트:** `OneCardEnginePlayTest.reaching_twenty_cards_is_bankruptcy_and_two_players_leaves_one_standing`,
+  `OneCardEngineDesertionTest`(9건)
+
 ## 11. 종료와 순위
 
```

```diff
 - **[결정] 총 차례 상한 600**: 내기·먹기 1회를 1차례로 센다(경쟁 창의 누름은 차례가 아니다). 600 에 닿으면
   `STALEMATE`. 실제 판은 수십~백여 차례라 안전장치다.
+
+- **코드:** 종료 판정 `OneCardEngine.endIfDecided`(LAST_STANDING·NO_HUMANS)·`finish`, 교착·상한은
+  `OneCardEngine.draw`·`play`, 순위 `rules/Ranking.rank`, 결과 `state/MatchResult`
+- **테스트:** `rules/RankingTest`(4건), `OneCardEnginePlayTest`(FINISHED, STALEMATE 두 경로),
+  `OneCardEngineDesertionTest`(LAST_STANDING, NO_HUMANS, 연속 패스 초기화)
 
 ## 12. 우리가 정한 것 (미규정·하우스 룰 선택)
```

```diff
 - 시작 카드 다시 나누기(§3-5)가 실제로 끝나는지 확인한다. 셔플을 주입해 첫 분배는 일반 카드를 모두 손패로
   보내고 다시 나눌 때는 다른 순서를 주는 테스트로 본다(같은 순서를 되풀이하면 같은 실패가 반복된다).
+
+- **코드:** `invariant/OneCardInvariantChecker`(카드 보존, 공격 누적·지정 무늬와 맨 위 카드, 차례 좌석)
+- **테스트:** `invariant/OneCardInvariantCheckerTest`(5건 — 고의 위반 4건 + 통과 1건),
+  `OneCardMatchSimulationTest`(10건 — 인원별 2,000판 + 사람 1·봇 300판, 매 전이 불변식 검사). 차례 상한 도달은
+  2인 1% 미만 · 3인 1.0% · 4인 2.1% · 5인 2.3% · 6인 5.1%다(전략 없는 무작위 판, 시드 고정 — 테스트는 10% 미만을
+  요구한다). 탈락자 손패를 더미로 옮기지 않는 고의 결함을 넣으면 10건이 모두 실패한다(D-127 검출력 확인).
```

- [ ] **Step 2: 설계서를 구현에 맞춘다** — `docs/plans/onecard.md`

설계서의 `CARDS_RECEIVED` 는 `HAND_UPDATED`(손패 전체 + `handVersion`)로 통일됐고(D-127), 지터는 정수
−100~100 이며, 패키지 몇 개는 만들지 않았다.

````diff
 ### 4.1 서버 패키지 `domain.game.onecard`
 
-스컬킹의 2계층(순수 룰 엔진 + 포트 어댑터)을 그대로 따른다.
+스컬킹의 2계층(순수 룰 엔진 + 포트 어댑터)을 그대로 따른다. 괄호는 만드는 단계다.
 
 ```
 onecard/
-  OneCardGameDefinition   @Component · ID "ONE_CARD" · 2~6인 · status COMING_SOON → S4 에서 AVAILABLE
-  OneCardEngine           순수 룰 엔진 — 저장소·시계 없음, 난수는 주입
-  OneCardGameEngine       포트 어댑터 — 상태 저장(Redis)·시계·로컬 발행
-  card/        PlayingCard(무늬·숫자·조커), Deck
-  state/       OneCardState, RaceWindow, Phase
-  action/      OneCardAction(sealed: PlayCard·Draw·CallOneCard·Catch), ActionValidator, RejectionReason
-  rules/       PlayRules(낼 수 있나) · AttackRules(누적·반격) · TurnOrder(J·Q·K·탈락 건너뛰기)
-  race/        RaceRules(창 열기·해소·봇 반응 추첨)
-  event/       OneCardEvent(sealed), OneCardMatchCompleted
-  bot/         OneCardBotPolicy(공개 정보 뷰만 본다)
-  invariant/   OneCardInvariantChecker(54장 보존 등)
-  lifecycle/   OneCardRoundStarter(GameStartingEvent → 분배)
-  persistence/ OneCardStateStore, OneCardMatchRecorder
-```
+  OneCardGameDefinition   @Component · ID "ONE_CARD" · 2~6인 · COMING_SOON → S4 에서 AVAILABLE (S3)
+  OneCardEngine           순수 룰 엔진 — 저장소·시계 없음, 난수는 주입 (S2)
+  OneCardGameEngine       포트 어댑터 — 상태 저장(Redis)·시계·로컬 발행 (S3)
+  Dealer · RaceSettings   분배·시작 카드 / 창 길이·봇 반응 구간·슬롯 수 (S2)
+  card/        Suit · Joker · PlayingCard(역할·공격 값과 세기) · Deck (S2)
+  state/       OneCardState · RaceWindow · Elimination · MatchResult (S2)
+  action/      OneCardAction(sealed: PlayCard·Draw·CallOneCard·Catch) · RejectionReason ·
+               OneCardActionRejectedException (S2)
+  rules/       PlayRules(낼 수 있나·반격) · TurnOrder(J·Q·K·탈락 건너뛰기) · Ranking(순위) (S2)
+  event/       OneCardEvent(sealed) (S2) · OneCardMatchCompleted (S3)
+  invariant/   OneCardInvariantChecker(54장 보존 등) (S2)
+  bot/         OneCardBotPolicy(공개 정보 뷰만 본다) (S3)
+  lifecycle/   OneCardRoundStarter(GameStartingEvent → 분배) (S3)
+  persistence/ OneCardStateStore, OneCardMatchRecorder (S3)
+```
+
+S2(D-127)에서 처음 그림과 달라진 점: 단계 enum(`Phase`)·`ActionValidator`·`AttackRules`·`race/` 패키지는
+만들지 않았다. 단계는 상태에서 파생하고(§4.2), 검증과 경쟁 창 처리는 엔진 안에, 공격 값·세기는 카드에 뒀다 —
+각각 한 곳에서만 쓰여 따로 뺄 이유가 없었다.
 
 ### 4.2 상태
 
-`record` + `with*` 불변 전이(CLAUDE.md 패턴).
-
-- `OneCardState`: 좌석별 손패 · 뽑을 더미 · 버린 더미 · 차례 좌석 · 방향(±1) · 지정 무늬(7) ·
-  공격 누적 장수와 세기 · K 보너스 차례 여부 · 경쟁 창 · 탈락 순서(파산·탈주) · 연속 패스 수 ·
-  총 차례 수 · 단계(`PLAYING`/`RACE`/`ENDED`)
-- `RaceWindow`: `raceId`(창 일련번호) · 주인 좌석 · 슬롯 · 지터 · 연 시각 · 창 길이 · **가장 빠른
-  봇의 좌석·행동·지연**(서버 전용)
+불변 레코드다. 한 전이가 여러 필드를 함께 바꾸므로 `with*` 대신 엔진 안에서만 가변 사본을 고쳐 새 레코드로
+얼린다(D-127).
+
+- `OneCardState`: 좌석별 손패 · 뽑을 더미(0번이 맨 위) · 버린 더미(마지막이 맨 위) · 차례 좌석(창이
+  열렸거나 끝났으면 −1) · 방향(±1) · 지정 무늬(7) · 공격 누적 장수 · 경쟁 창(없으면 null) · 탈락 목록(탈락 순) ·
+  연속 패스 수 · 총 차례 수 · 버전(전이마다 +1, 비공개 손패 이벤트의 `handVersion`) · 결과(끝나기 전엔 null).
+  공격 세기는 맨 위 카드에서, K 보너스 차례는 차례 좌석에서, 단계(`PLAYING`/`RACE`/`ENDED`)는 창·결과
+  유무에서 파생한다.
+- `RaceWindow`: `raceId`(창을 연 전이의 버전) · 주인 좌석 · 슬롯 · 지터 · 연 시각 · 창 길이 · 낸 순간 정해 둔
+  다음 차례(§9.2) · **가장 빠른 봇의 좌석·행동·지연**(서버 전용)
 
 첫 누름이 창을 닫으므로 봇은 가장 빠른 한 명만 기억하면 된다.
````

```diff
 | 클라→서버 | `CALL_ONE_CARD` | `raceId`. 창 주인만 |
 | 클라→서버 | `CATCH` | `raceId`. 창 주인 외 |
-| 공개 | `CARD_PLAYED` | 좌석, 카드, 지정 무늬, 공격 누적 |
-| 공개 | `CARDS_DRAWN` | 좌석, **장수만**, 사유(`TURN`/`ATTACK`/`PENALTY`) |
-| 공개 | `TURN_CHANGED` | 좌석, 방향 |
+| 공개 | `MATCH_STARTED` | 첫 차례 좌석, 시작 카드, 손패 장수, 뽑을 더미 장수 |
+| 공개 | `CARD_PLAYED` | 좌석, 카드, 지정 무늬, 낸 뒤 손패 장수, 공격 누적 |
+| 공개 | `CARDS_DRAWN` | 좌석, **장수만**, 사유(`TURN`/`ATTACK`/`PENALTY`), 먹은 뒤 손패 장수, 뽑을 더미 장수 |
+| 공개 | `TURN_CHANGED` | 좌석, 방향, 공격 누적 |
 | 공개 | `RACE_OPENED` | `raceId`, 주인 좌석, 슬롯, 지터, 창 길이 |
-| 공개 | `RACE_RESOLVED` | `raceId`, 결과(`CALLED`/`CAUGHT`/`EXPIRED`), 누른 좌석 |
+| 공개 | `RACE_RESOLVED` | `raceId`, 결과(`CALLED`/`CAUGHT`/`EXPIRED`, 창 중 탈주면 `CANCELLED`), 누른 좌석 |
 | 공개 | `PILE_RESHUFFLED` | 뽑을 더미 장수 |
-| 공개 | `PLAYER_ELIMINATED` | 좌석, 사유(`BANKRUPT`/`DESERTED`) |
+| 공개 | `PLAYER_ELIMINATED` | 좌석, 사유(`BANKRUPT`/`DESERTED`), 탈락 때 들고 있던 장수 |
 | 공개 | `MATCH_ENDED` | 순위, 사유(`FINISHED`/`LAST_STANDING`/`STALEMATE`/`NO_HUMANS`) |
-| 비공개 | `HAND_DEALT` | 내 손패 |
-| 비공개 | `CARDS_RECEIVED` | 내가 먹은 카드 |
+| 비공개 | `HAND_DEALT` | 내 손패 전체 + `handVersion` |
+| 비공개 | `HAND_UPDATED` | 내 손패 전체 + 새로 받은 카드 + `handVersion`(내기·먹기·벌칙·탈락마다, D-127) |
 | 비공개 | `ERROR` | `NOT_YOUR_TURN`, `RACE_IN_PROGRESS`, `NO_RACE` 등 |
 
```

```diff
 - **비공개 이벤트는 공개 순번을 쓰지 않는다**(§4.5b). 지금은 비공개 이벤트도 방 공통 순번을
   소비해, 그 이벤트를 못 받는 다른 클라에게는 다음 공개 이벤트가 구멍(gap)으로 보이고 resync 를
-  부른다. 원카드는 먹을 때마다 비공개 `CARDS_RECEIVED` 가 나가므로 이대로면 거의 매 차례 전원이
+  부른다. 원카드는 내거나 먹을 때마다 비공개 `HAND_UPDATED` 가 나가므로 이대로면 매 차례 전원이
   resync 한다.
-- **비공개 payload 는 손패 전체 + `handVersion`**(좌석별 단조 증가). 클라는 가진 것보다 낮은
+- **비공개 payload 는 손패 전체 + `handVersion`**(상태 버전이라 좌석마다 단조 증가). 클라는 가진 것보다 낮은
   버전을 버린다 — 순번이 없어도 resync 스냅샷(비공개 뷰에도 `handVersion`)과 순서가 뒤바뀌어
   손패가 되돌아가지 않는다.
```

```diff
 
 - 주인의 `CALL_ONE_CARD` 가 먼저면 `CALLED`(안전). 다른 사람의 `CATCH` 가 먼저면 `CAUGHT` —
-  주인이 1장 먹는다(비공개 `CARDS_RECEIVED` + 공개 `CARDS_DRAWN(PENALTY)`).
+  주인이 1장 먹는다(비공개 `HAND_UPDATED` + 공개 `CARDS_DRAWN(PENALTY)`).
 - "먼저"는 **방 액션 락을 먼저 잡은 요청**이다. 락 경합으로 `BUSY` 를 받으면 클라는 창이 아직
   열려 있는 동안 짧게 재시도한다(최대 2회 — 락을 쥔 쪽이 창과 무관한 거절 처리일 수 있다).
```

```diff
   봇이 3초 안이면 그 봇이 누른다.
 - 슬롯: 클라가 게임판 기준 상대 좌표로 정의한 N개(프로토콜 상수, 기본 8) 중 서버가 하나를
-  고르고, 지터(−1~1 로 정규화한 x·y 두 값, 클라가 슬롯 반경으로 환산)를 더한다. 전원이 같은
+  고르고, 지터(x·y 각각 −100~100 정수, 클라가 슬롯 반경의 백분율로 환산)를 더한다. 전원이 같은
   위치를 받으므로 위치 운은 공평하다.
 
```

```diff
 - `GameEventBroadcaster` 는 `sequenced()` 가 false 인 이벤트에 `RoomSeq.next` 를 부르지 않고
   seq 를 비운다(`NON_NULL` 이라 JSON 에서 빠진다). 게임 이름을 보지 않는 판단이다.
-- 원카드는 비공개 이벤트(`HAND_DEALT`·`CARDS_RECEIVED`)만 false 로 둔다.
+- 원카드는 비공개 이벤트(`HAND_DEALT`·`HAND_UPDATED`)만 false 로 둔다.
 - **기본값이 true 인 이유**: 티츄는 이 resync 에 기대고 있다(§7). 기본을 바꾸면 티츄 동작이 바뀐다.
 
```

```diff
 - **resync 가 잠금 없이 상태와 순번을 따로 읽는다**(모든 게임 공통). 원카드는 결과값 payload 로
   흡수하고(§4.3), 근본 수정(상태 저장과 순번 발급을 한 번에)은 별도 과제로 둔다.
+- **D-126 과 겹치는 부분**(2026-10-04 기준 다른 세션에서 진행 중): 티츄 resync 수정이 §4.5b 와 같은
+  `GameEvent.sequenced()` 기본 메서드와 "resync 를 방 락 안에서" 읽는 변경을 넣는다. 그쪽이 먼저 병합되면 S3 는
+  포트 확장을 새로 만들지 않고 원카드 비공개 이벤트에서 `sequenced()` 를 false 로 재정의만 하며, 위 두 위험
+  항목도 그쪽에서 닫힌다. S2 코드는 이 메서드를 쓰지 않으므로 병합 순서와 무관하다.
```

- [ ] **Step 3: `rules` 묶음과 테스트 명령** — `scripts/check.sh`, `CLAUDE.md`

`scripts/check.sh`:

```diff
   fast              빠른 회귀 (클라 tsc+vitest + 서버 compile, ~30s)
                     pre-commit hook 과 동일 로직.
-  rules             서버 룰 도메인 단위 (티츄 + 스컬킹 전량 + 봇 강도 평가, ~15s)
+  rules             서버 룰 도메인 단위 (티츄 + 스컬킹 + 원카드 전량 + 봇 강도 평가, ~20s)
                     Docker 불필요.
   server            서버 풀 (단위 + IT, Docker 필요, ~1m20s)
```

```diff
         # 스컬킹도 티츄처럼 하위 패키지를 명시한다 — skullking.* 로 쓸면 persistence 의
         # SkullKingMatchRecorderIT(D-115, Testcontainers)까지 잡혀 "Docker 불필요"가 거짓이 된다.
+        # 원카드는 아직(S2, D-127) 순수 테스트뿐이라 onecard.* 로 묶는다 — S3 에서 IT(기록기·라운드
+        # 시작)가 생기면 같은 이유로 하위 패키지를 명시할 것.
         ./gradlew :server:test \
             --tests "com.mirboard.domain.game.tichu.card.*" \
```

```diff
             --tests "com.mirboard.domain.game.skullking.state.*" \
             --tests "com.mirboard.domain.game.skullking.trick.*" \
-            --tests "com.mirboard.domain.game.skullking.persistence.SkullKingJsonRoundTripTest"
+            --tests "com.mirboard.domain.game.skullking.persistence.SkullKingJsonRoundTripTest" \
+            --tests "com.mirboard.domain.game.onecard.*"
         log "모두 통과"
         ;;
```

`CLAUDE.md`:

```diff
 **Mirboard** — 웹 기반 턴제 보드게임 플랫폼. 공통 허브/로비 + **게임 2종**: 티츄(4인 2:2 팀전), 스컬킹(2~8인 개인전).
 
-현재는 **동작하는 MVP** 상태이며 상용화 트랙(A/C/D/E/G) 진행 중이다(설계 Phase 1 ~ 클라 통합·UI 리디자인 Phase 20 완료, 이후 M0~M5 전부 완료, 결정 이력 D-122까지). 로비/방 → 두 게임 풀게임 → 점수·ELO 영속(게임별, D-115) → 봇 자동 채움 → 재접속/탈주 → 라이트/다크 UI 까지 end-to-end로 연결되어 있다. 멀티게임(트랙 E)은 **완료** — 포트 추출(D-98) 후 스컬킹을 룰 명세(D-100)·순수 엔진(D-101)·탈주(D-104)·인게임 배선(D-102)·클라 게임판(D-103)까지 붙였다. 스컬킹 매치 영속·ELO 는 게임별 전적 테이블(`user_game_stats`, D-115)로 해소.
+현재는 **동작하는 MVP** 상태이며 상용화 트랙(A/C/D/E/G) 진행 중이다(설계 Phase 1 ~ 클라 통합·UI 리디자인 Phase 20 완료, 이후 M0~M5 전부 완료, 결정 이력 D-127까지). 로비/방 → 두 게임 풀게임 → 점수·ELO 영속(게임별, D-115) → 봇 자동 채움 → 재접속/탈주 → 라이트/다크 UI 까지 end-to-end로 연결되어 있다. 멀티게임(트랙 E)은 **완료** — 포트 추출(D-98) 후 스컬킹을 룰 명세(D-100)·순수 엔진(D-101)·탈주(D-104)·인게임 배선(D-102)·클라 게임판(D-103)까지 붙였다. 스컬킹 매치 영속·ELO 는 게임별 전적 테이블(`user_game_stats`, D-115)로 해소.
 
 - **서버** `server/` (Spring Boot 4 / Java 25, Gradle): 도메인 `domain.lobby`·`domain.game.{core,tichu,scoring}`, 인프라 `infra.{rest,ws,bot,messaging,metrics,config,web}`.
```

```diff
 ./gradlew :server:test --tests "com.mirboard.domain.game.tichu.bot.*"       # 티츄 봇 정책·순수 시뮬레이션 (D-118, Docker 불필요)
 ./gradlew :server:test --tests "com.mirboard.domain.game.skullking.bot.*"   # 스컬킹 봇 정책·강도 평가 (D-119, Docker 불필요)
+./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"         # 원카드 순수 엔진·시뮬레이션 (D-127, Docker 불필요)
 MIRBOARD_BOT_EVAL=1 ./gradlew :server:test --rerun --tests "com.mirboard.domain.game.tichu.bot.HeuristicBotEvaluationTest"   # 티츄 봇 대형 평가 (~1m30s)
 ./gradlew :server:test --tests "com.mirboard.domain.game.tichu.DealingLifecycleTest"
```

- [ ] **Step 4: 룰 묶음 확인**

Run: `./scripts/check.sh rules`
Expected: 마지막 줄 `──[check:rules]── 모두 통과`. 이어서 `ls server/build/test-results/test/ | grep -c 'game.onecard'`
가 `11` 이다(원카드 테스트 클래스 11개 — Gradle 은 실행마다 결과 폴더를 비우므로 이번 묶음에 든 것만 남는다).

- [ ] **Step 5: 서버 전체 실측과 수치 반영**

Run: `./scripts/check.sh server` (Docker 필요, 약 2분 — 이 스크립트가 OrbStack/Colima 소켓을 잡아 준다. `./gradlew`
를 직접 부르면 Testcontainers 가 Docker 를 못 찾아 IT 가 실패한다) 뒤에 집계:

```bash
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
t = s = f = 0
for p in glob.glob('server/build/test-results/test/*.xml'):
    r = ET.parse(p).getroot()
    t += int(r.get('tests')); s += int(r.get('skipped')); f += int(r.get('failures')) + int(r.get('errors'))
print(f"tests={t} skipped={s} failed={f}")
EOF
```

Expected: `tests=1111 skipped=5 failed=0`. 기준은 이 브랜치가 갈라진 main 의 수치(README·케이스 스터디의 1014건,
그중 Docker 불필요 831건)이고, 원카드 97건은 전부 Docker 불필요다 —
`grep -rl Testcontainers server/src/test/java/com/mirboard/domain/game/onecard` 출력이 비어 있어야 한다. 그래서
Docker 불필요 수는 831 + 97 = 928(1111 의 84%)이다. main 이 그사이 바뀌어 기준 수치가 달라졌다면 바뀐 main 값에
97 을 더해 아래 네 문서를 쓴다.

`docs/implementation-status.md`:

```diff
 
 > 지금까지 **실제로 구현된 기능**을 end-to-end로 정리한 현황 문서.
-> 구조/흐름은 `docs/architecture.md`, 의사결정 이력은 `docs/decisions.md`(D-01~D-122),
+> 구조/흐름은 `docs/architecture.md`, 의사결정 이력은 `docs/decisions.md`(D-01~D-127),
 > 단계별 진행은 `docs/plans/mvp-roadmap.md` 참조.
 > 기능 설명의 세부 계약은 `docs/api.md`(REST), `docs/stomp-protocol.md`(STOMP),
-> `docs/game-port.md`(`GameEngine` 포트), `docs/rules-tichu.md`·`docs/rules-skullking.md`(룰)가
-> 정본이다.
+> `docs/game-port.md`(`GameEngine` 포트), `docs/rules-tichu.md`·`docs/rules-skullking.md`·
+> `docs/rules-onecard.md`(룰)가 정본이다.
 
 ---
```

```diff
 | 5 | 티츄 룰 엔진 (전 페이즈 + 특수 카드) | ✅ | `domain.game.tichu` |
 | 5b | 스컬킹 (룰 엔진 + 배선 + 클라 게임판) | ✅ | `domain.game.skullking`, `features/skullking` (§16) |
+| 5c | 원카드 순수 룰 엔진 (S2 — 포트 어댑터·클라는 S3·S4) | 🟡 | `domain.game.onecard`, `docs/rules-onecard.md` (D-127) |
 | 6 | 봇 플레이어 (빈 좌석 자동 채움) | ✅ | `infra.bot`, `domain.game.tichu.bot` |
 | 7 | 재접속 동기화 (resync) | ✅ | `RoomService`, `GET /rooms/{id}/resync` |
```

```diff
 ## 13. 테스트 현황
 
-- **서버**: **1014건** (D-122 시점 실측, 실패 0, 대형 봇 평가 5건은 `MIRBOARD_BOT_EVAL=1` 전용이라 skip). 스컬킹 도메인 375건(그중 Docker 불필요 371건). 테스트 JVM 힙 1g · 컨텍스트 캐시 상한 4(IT 가 늘어 기본 512m 에서 OOM — D-122 검증 중 발견).
+- **서버**: **1111건** (D-127 시점 실측, 실패 0, 대형 봇 평가 5건은 `MIRBOARD_BOT_EVAL=1` 전용이라 skip). 스컬킹 도메인 375건(그중 Docker 불필요 371건), 원카드 도메인 97건(전부 Docker 불필요). 테스트 JVM 힙 1g · 컨텍스트 캐시 상한 4(IT 가 늘어 기본 512m 에서 OOM — D-122 검증 중 발견).
   단위(룰 엔진·족보·ELO·JWT·카탈로그·포트 어댑터) + 통합(Testcontainers PostgreSQL 16/
   Redis — auth/rooms/STOMP/봇/동시성/매치 영속/2-인스턴스 인계).
-- 룰·봇 단위는 **Docker 불필요** — `./scripts/check.sh rules` 에 묶여 있다(티츄·스컬킹 룰 + 두 봇
-  평가, ~15s). 스컬킹 매치 기록 IT(D-115)는 Docker 가 필요해 `rules` 에서 뺐다.
+- 룰·봇 단위는 **Docker 불필요** — `./scripts/check.sh rules` 에 묶여 있다(티츄·스컬킹·원카드 룰 + 두 봇
+  평가, ~20s). 스컬킹 매치 기록 IT(D-115)는 Docker 가 필요해 `rules` 에서 뺐다.
 - **클라이언트**: **420건 / 45파일** (D-124 시점 실측, 실패 0). Vitest + RTL — 스토어
   리듀서, 족보 타입, 카드 에셋 매핑 등.
```

`README.md`:

```diff
 
 Spring Boot 4 / Java 25 · PostgreSQL · Redis · React + TypeScript ·
-서버 테스트 1014건 / 클라 420건
+서버 테스트 1111건 / 클라 420건
 
 **라이브**: https://mirboard.fly.dev — 로그인 화면 「게스트로 바로 체험하기」로 가입 없이 들어갈 수 있습니다.
```

`docs/case-study-multi-game.md`:

```diff
 (+ 오탐 방지 통과 2건)으로 검출 능력 자체를 테스트했습니다.
 
-**여기 한 줄만 숫자를 씁니다** — 서버 테스트 **1014건 중 831건(82%)이 Docker 불필요**합니다.
+**여기 한 줄만 숫자를 씁니다** — 서버 테스트 **1111건 중 928건(84%)이 Docker 불필요**합니다.
 자랑이 아니라 §2 의 2계층 분리가 값을 냈다는 인과 증거입니다.
 
```

`docs/plans/mvp-roadmap.md`:

```diff
 | M5 | E | 멀티게임: 포트 졸업 → 디스패치 seam 포트화 → 스컬킹(2~8인) | ✅ S0~S6 완료(D-97~D-104): 포트·인원 가변·룰 명세·순수 엔진(305건)·탈주(유령 좌석)·인게임 배선(봇 풀매치 IT)·클라 게임판(Row-Flow, 실측 완료). 실행 단위 `docs/plans/multi-game-sessions.md`. **잔여 별건**: 스컬킹 매치 영속·ELO(D-02 게임별 rating 분리 선행), 끊김 유예 구간 정지(D-104 한계), 요트/할리갈리 |
 | M6 | E·A | 스컬킹 완성도: ① 게임별 전적·레이팅(매치 영속·개인전 ELO·게임별 랭킹) ② 라운드 점수표 ③ 봇 휴리스틱 ④ 튜토리얼 | ✅ ① D-115 게임별 전적·레이팅 · ② D-120 라운드 결과(다음 라운드 예측 중 비차단)·점수표·종료 후 게임판 유지 · ③ D-119 봇 휴리스틱(공개 정보 뷰 + `TrickResolver` 승률) · ④ D-121 게임별 튜토리얼 레지스트리 + 스컬킹 13단계·퀴즈 (2026-10-03). 후속: 봇 상대 모델링(8인 대 최약수 대등), 티츄 봇 방 종료 화면 유지 |
-| M7 | E | 세 번째 게임 원카드: 표준 기본형 룰 + "원카드!/잡기!" 실시간 경쟁(무작위 위치 버튼, 봇 반응 시간) + 포트 엔진 타이머 확장 | 🟡 설계 승인(D-123, `docs/plans/onecard.md`) · **S0 완료**(D-124 순번 판정을 훅으로) · **S1 완료**(D-125 `docs/rules-onecard.md`). 다음: S2 순수 엔진 → S3 포트 타이머·통합 → S4 클라 → S5 통합·배포 |
+| M7 | E | 세 번째 게임 원카드: 표준 기본형 룰 + "원카드!/잡기!" 실시간 경쟁(무작위 위치 버튼, 봇 반응 시간) + 포트 엔진 타이머 확장 | 🟡 설계 승인(D-123, `docs/plans/onecard.md`) · **S0 완료**(D-124 순번 판정을 훅으로) · **S1 완료**(D-125 `docs/rules-onecard.md`) · **S2 완료**(D-127 순수 엔진, 테스트 97건). 다음: S3 포트 타이머·통합 → S4 클라 → S5 통합·배포 |
 
 **M0 상세(완료)**: D-83(`SecurityConfig`/`WebSocketConfig` origin 화이트리스트+헤더),
```

- [ ] **Step 6: 커밋**

```bash
git add docs/rules-onecard.md docs/plans/onecard.md scripts/check.sh CLAUDE.md \
  docs/implementation-status.md README.md docs/case-study-multi-game.md docs/plans/mvp-roadmap.md
git commit -m "docs(D-127): 원카드 S2 마감 — 룰↔코드 매핑·설계서 동기화·rules 묶음·테스트 수치

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 7: Phase Gate**

`git log --oneline main..HEAD` 로 태스크 커밋 9개(리뷰 수정 커밋이 있으면 그만큼 더)를 확인하고 사용자에게 보고한다 — 만든 것(패키지·
테스트 97건), 실측 수치, 다음 단계(S3 포트 타이머·어댑터·봇·기록기). **사용자 승인 전에는 main 에 병합하지
않는다.**
