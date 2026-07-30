package com.mirboard.domain.game.skullking.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.TrickResult;
import com.mirboard.domain.game.skullking.trick.TrickResolver;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 보너스 (§11, §13-⑨⑩). */
class BonusCalculatorTest {

    private static PlayedCard suit(int seat, SkullSuit s, int rank) {
        return PlayedCard.of(seat, SkullCard.of(s, rank));
    }

    private static PlayedCard pirate(int seat) {
        return PlayedCard.of(seat, SkullCard.pirate());
    }

    private static PlayedCard mermaid(int seat) {
        return PlayedCard.of(seat, SkullCard.mermaid());
    }

    private static PlayedCard skullKing(int seat) {
        return PlayedCard.of(seat, SkullCard.skullKing());
    }

    /** 실제 판정을 거쳐 트릭 결과를 만든다 — 승리 카드를 손으로 지정하면 틀려도 못 잡는다. */
    private static TrickResult resolved(List<PlayedCard> played) {
        return TrickResolver.resolve(played);
    }

    private static int bonus(List<PlayedCard> played) {
        return BonusCalculator.bonusFor(List.of(resolved(played)));
    }

    @Nested
    @DisplayName("색상 14 — 단순 포함")
    class FourteenCards {

        @Test
        void non_black_fourteens_are_ten_each() {
            assertThat(bonus(List.of(suit(0, SkullSuit.GREEN, 14), suit(1, SkullSuit.GREEN, 2))))
                    .isEqualTo(10);
        }

        @Test
        void black_fourteen_is_twenty() {
            assertThat(bonus(List.of(suit(0, SkullSuit.BLACK, 14), suit(1, SkullSuit.BLACK, 2))))
                    .isEqualTo(20);
        }

        @Test
        void several_fourteens_in_one_trick_all_count() {
            int result = bonus(List.of(
                    suit(0, SkullSuit.GREEN, 14),
                    suit(1, SkullSuit.YELLOW, 14),
                    suit(2, SkullSuit.PURPLE, 14),
                    suit(3, SkullSuit.BLACK, 14)));

            assertThat(result).isEqualTo(10 + 10 + 10 + 20);
        }

        @Test
        void thirteen_earns_nothing() {
            assertThat(bonus(List.of(suit(0, SkullSuit.BLACK, 13), suit(1, SkullSuit.BLACK, 2))))
                    .isZero();
        }

        @Test
        void a_fourteen_the_winner_played_themselves_still_counts() {
            assertThat(bonus(List.of(suit(0, SkullSuit.GREEN, 14), suit(1, SkullSuit.GREEN, 1))))
                    .as("자기가 낸 14 도 그 트릭을 가져갔으면 센다")
                    .isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("캐릭터 포획 — 관계 기준 (§13-⑨)")
    class CharacterCapture {

        @Test
        void a_pirate_that_beats_a_mermaid_earns_twenty() {
            assertThat(bonus(List.of(mermaid(0), pirate(1))))
                    .isEqualTo(BonusCalculator.MERMAID_TAKEN_BY_PIRATE);
        }

        @Test
        void a_pirate_beating_two_mermaids_earns_forty() {
            assertThat(bonus(List.of(mermaid(0), mermaid(1), pirate(2)))).isEqualTo(40);
        }

        @Test
        void a_skull_king_that_beats_pirates_earns_thirty_each() {
            assertThat(bonus(List.of(pirate(0), skullKing(1)))).isEqualTo(30);
            assertThat(bonus(List.of(pirate(0), pirate(1), skullKing(2)))).isEqualTo(60);
        }

        @Test
        void a_mermaid_that_beats_the_skull_king_earns_forty() {
            assertThat(bonus(List.of(skullKing(0), mermaid(1))))
                    .isEqualTo(BonusCalculator.SKULL_KING_TAKEN_BY_MERMAID);
        }

        /**
         * 명세 함정 #7 의 그 케이스. 인어·스컬킹·해적이 다 나와 인어가 이기면 승자 더미에
         * 셋이 다 들어 있다. 포함 기준이면 40+30+20 = 90, 관계 기준이면 40.
         * 진 해적은 아무것도 잡지 않았으므로 40 이 정답이다.
         */
        @Test
        void the_three_way_trick_pays_forty_not_ninety() {
            int result = bonus(List.of(pirate(0), skullKing(1), mermaid(2)));

            assertThat(result)
                    .as("인어가 스컬킹을 잡은 40점만. 진 해적은 보너스의 주체가 아니다")
                    .isEqualTo(40);
        }

        @Test
        void a_suit_card_winner_earns_no_capture_bonus() {
            assertThat(bonus(List.of(suit(0, SkullSuit.GREEN, 5), suit(1, SkullSuit.GREEN, 3))))
                    .isZero();
        }

        @Test
        void an_all_escape_trick_earns_nothing() {
            assertThat(bonus(List.of(
                    PlayedCard.of(0, SkullCard.escape()),
                    PlayedCard.of(1, SkullCard.escape())))).isZero();
        }

        @Test
        void a_losing_mermaid_does_not_pay_the_pirate_twice() {
            // 해적이 이기고 인어 1장 + 색상 카드 → 20점만.
            assertThat(bonus(List.of(mermaid(0), suit(1, SkullSuit.BLACK, 13), pirate(2))))
                    .isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("티그리스는 선언값으로 센다 (§13-⑩)")
    class Tigress {

        @Test
        void declared_as_pirate_counts_toward_the_skull_king_capture() {
            int result = bonus(List.of(
                    PlayedCard.tigress(0, TigressMode.PIRATE), skullKing(1)));

            assertThat(result)
                    .as("해적 선언 티그리스도 '스컬킹으로 잡은 해적' 1장")
                    .isEqualTo(BonusCalculator.PIRATE_TAKEN_BY_SKULL_KING);
        }

        @Test
        void declared_as_pirate_earns_the_mermaid_capture_when_it_wins() {
            int result = bonus(List.of(
                    mermaid(0), PlayedCard.tigress(1, TigressMode.PIRATE)));

            assertThat(result)
                    .as("그 티그리스로 인어를 잡아 이겼으면 20점도 성립한다")
                    .isEqualTo(BonusCalculator.MERMAID_TAKEN_BY_PIRATE);
        }

        @Test
        void declared_as_escape_is_not_counted_as_a_pirate() {
            int result = bonus(List.of(
                    PlayedCard.tigress(0, TigressMode.ESCAPE), skullKing(1)));

            assertThat(result)
                    .as("탈출 선언이면 스컬킹이 잡은 해적으로 세지 않는다")
                    .isZero();
        }

        @Test
        void a_real_pirate_and_a_declared_tigress_both_count_for_the_skull_king() {
            int result = bonus(List.of(
                    pirate(0), PlayedCard.tigress(1, TigressMode.PIRATE), skullKing(2)));

            assertThat(result).isEqualTo(60);
        }
    }

    @Nested
    @DisplayName("여러 트릭 합산")
    class MultipleTricks {

        @Test
        void bonuses_add_up_across_the_round() {
            List<TrickResult> won = List.of(
                    resolved(List.of(mermaid(0), pirate(1))),
                    resolved(List.of(suit(0, SkullSuit.BLACK, 14), suit(1, SkullSuit.BLACK, 2))));

            assertThat(BonusCalculator.bonusFor(won)).isEqualTo(20 + 20);
        }

        @Test
        void no_tricks_means_no_bonus() {
            assertThat(BonusCalculator.bonusFor(List.of())).isZero();
        }

        @Test
        void a_single_trick_can_stack_fourteen_and_capture_bonuses() {
            List<PlayedCard> played = List.of(
                    mermaid(0), suit(1, SkullSuit.BLACK, 14), pirate(2));

            assertThat(bonus(played))
                    .as("해적이 이겨서 인어 20 + 트릭에 들어온 검정 14 의 20")
                    .isEqualTo(40);
        }
    }
}
