package com.mirboard.domain.game.skullking.trick;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.TrickResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 트릭 승자 판정 사다리 (§7, §8). */
class TrickResolverTest {

    private static PlayedCard suit(int seat, SkullSuit s, int rank) {
        return PlayedCard.of(seat, SkullCard.of(s, rank));
    }

    private static PlayedCard escape(int seat) {
        return PlayedCard.of(seat, SkullCard.escape());
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

    private static int winner(List<PlayedCard> played) {
        return TrickResolver.resolve(played).winnerSeat();
    }

    @Nested
    @DisplayName("§7 전수 대조표 — SK/P/M 8조합")
    class ExhaustiveEightCombinations {

        /**
         * 명세 §7 의 전수 대조표를 그대로 옮긴다. 좌석 0 에 항상 색상 카드를 깔아 리드
         * 수트를 고정하고, 1·2·3 에 스컬킹/해적/인어를 조합으로 얹는다.
         */
        @ParameterizedTest(name = "SK={0} P={1} M={2} → seat {3}")
        @CsvSource({
                // sk,     p,     m,     expectedWinnerSeat
                "false, false, false, 0",   // 색상만 → §7.1
                "false, false, true,  3",   // 인어 > 모든 색상
                "false, true,  false, 2",   // 해적 > 모든 색상
                "false, true,  true,  2",   // 해적 > 인어
                "true,  false, false, 1",   // 스컬킹 > 모든 색상
                "true,  false, true,  3",   // 인어 > 스컬킹
                "true,  true,  false, 1",   // 스컬킹 > 해적
                "true,  true,  true,  3"    // 명시 예외 — 셋 다 나오면 인어
        })
        void matches_the_spec_table(boolean sk, boolean p, boolean m, int expectedSeat) {
            List<PlayedCard> played = new ArrayList<>();
            played.add(suit(0, SkullSuit.GREEN, 10));
            played.add(sk ? skullKing(1) : suit(1, SkullSuit.GREEN, 2));
            played.add(p ? pirate(2) : suit(2, SkullSuit.GREEN, 3));
            played.add(m ? mermaid(3) : suit(3, SkullSuit.GREEN, 4));

            assertThat(winner(played)).isEqualTo(expectedSeat);
        }
    }

    @Nested
    @DisplayName("사다리 각 단 — 양성/음성 각 3건 이상")
    class LadderRungs {

        @Test
        void rung1_skull_king_plus_mermaid_yields_mermaid_positive() {
            assertThat(winner(List.of(skullKing(0), mermaid(1)))).isEqualTo(1);
            assertThat(winner(List.of(mermaid(0), skullKing(1)))).isEqualTo(0);
            assertThat(winner(List.of(pirate(0), skullKing(1), mermaid(2)))).isEqualTo(2);
        }

        @Test
        void rung1_negative_when_either_is_absent() {
            assertThat(winner(List.of(skullKing(0), pirate(1)))).isEqualTo(0);
            assertThat(winner(List.of(mermaid(0), pirate(1)))).isEqualTo(1);
            assertThat(winner(List.of(skullKing(0), suit(1, SkullSuit.BLACK, 14)))).isZero();
        }

        @Test
        void rung2_skull_king_positive() {
            assertThat(winner(List.of(skullKing(0), pirate(1), suit(2, SkullSuit.BLACK, 14))))
                    .isZero();
            assertThat(winner(List.of(pirate(0), skullKing(1)))).isEqualTo(1);
            assertThat(winner(List.of(suit(0, SkullSuit.GREEN, 1), skullKing(1)))).isEqualTo(1);
        }

        @Test
        void rung2_negative_without_skull_king() {
            assertThat(winner(List.of(pirate(0), mermaid(1)))).isZero();
            assertThat(winner(List.of(mermaid(0), suit(1, SkullSuit.BLACK, 14)))).isZero();
            assertThat(winner(List.of(suit(0, SkullSuit.GREEN, 5), escape(1)))).isZero();
        }

        @Test
        void rung3_pirate_positive() {
            assertThat(winner(List.of(pirate(0), mermaid(1), suit(2, SkullSuit.BLACK, 14))))
                    .isZero();
            assertThat(winner(List.of(suit(0, SkullSuit.GREEN, 9), pirate(1)))).isEqualTo(1);
            assertThat(winner(List.of(escape(0), pirate(1), suit(2, SkullSuit.GREEN, 3))))
                    .isEqualTo(1);
        }

        @Test
        void rung3_negative_when_skull_king_outranks_or_no_pirate() {
            assertThat(winner(List.of(pirate(0), skullKing(1)))).isEqualTo(1);
            assertThat(winner(List.of(mermaid(0), suit(1, SkullSuit.GREEN, 14)))).isZero();
            assertThat(winner(List.of(suit(0, SkullSuit.GREEN, 2), suit(1, SkullSuit.GREEN, 3))))
                    .isEqualTo(1);
        }

        @Test
        void rung4_mermaid_positive() {
            assertThat(winner(List.of(mermaid(0), suit(1, SkullSuit.BLACK, 14)))).isZero();
            assertThat(winner(List.of(suit(0, SkullSuit.GREEN, 7), mermaid(1)))).isEqualTo(1);
            assertThat(winner(List.of(escape(0), mermaid(1), suit(2, SkullSuit.BLACK, 1))))
                    .isEqualTo(1);
        }

        @Test
        void rung4_negative_when_a_pirate_is_present() {
            assertThat(winner(List.of(mermaid(0), pirate(1)))).isEqualTo(1);
            assertThat(winner(List.of(mermaid(0), skullKing(1), pirate(2)))).isZero();
            assertThat(winner(List.of(suit(0, SkullSuit.GREEN, 5)))).isZero();
        }

        @Test
        void rung6_all_escapes_first_one_wins() {
            assertThat(winner(List.of(escape(0), escape(1), escape(2)))).isZero();
            assertThat(winner(List.of(escape(2), escape(0), escape(1)))).isEqualTo(2);
            assertThat(winner(List.of(escape(1), escape(3)))).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("§7.1 색상 카드끼리")
    class SuitOnly {

        @Test
        void highest_of_the_lead_suit_wins() {
            List<PlayedCard> played = List.of(
                    suit(0, SkullSuit.GREEN, 5),
                    suit(1, SkullSuit.GREEN, 12),
                    suit(2, SkullSuit.GREEN, 3));

            assertThat(winner(played)).isEqualTo(1);
        }

        @Test
        void off_suit_non_black_loses_regardless_of_rank() {
            List<PlayedCard> played = List.of(
                    suit(0, SkullSuit.GREEN, 2),
                    suit(1, SkullSuit.YELLOW, 14),
                    suit(2, SkullSuit.PURPLE, 13));

            assertThat(winner(played))
                    .as("리드 수트가 아닌 비-검정은 14 라도 진다")
                    .isZero();
        }

        /** §13-⑦ — 각주 [4]가 아니라 본문(으뜸패 정의)이 우선한다. */
        @Test
        void black_beats_other_suits_even_when_off_suit() {
            List<PlayedCard> played = List.of(
                    suit(0, SkullSuit.GREEN, 11),
                    suit(1, SkullSuit.BLACK, 3));

            assertThat(winner(played))
                    .as("검정 3 이 리드 초록 11 을 이긴다 (§13-⑦ 명시 케이스)")
                    .isEqualTo(1);
        }

        @Test
        void highest_black_wins_among_multiple_blacks() {
            List<PlayedCard> played = List.of(
                    suit(0, SkullSuit.GREEN, 14),
                    suit(1, SkullSuit.BLACK, 2),
                    suit(2, SkullSuit.BLACK, 9),
                    suit(3, SkullSuit.BLACK, 5));

            assertThat(winner(played)).isEqualTo(2);
        }

        @Test
        void black_lead_is_contested_among_blacks_only() {
            List<PlayedCard> played = List.of(
                    suit(0, SkullSuit.BLACK, 4),
                    suit(1, SkullSuit.GREEN, 14),
                    suit(2, SkullSuit.BLACK, 6));

            assertThat(winner(played)).isEqualTo(2);
        }

        /** §13-⑧ — 탈출이 섞여 원문 조건절이 깨진 트릭. 탈출을 선제외하고 색상 규칙 적용. */
        @Test
        void escapes_are_excluded_before_comparing_suit_cards() {
            List<PlayedCard> played = List.of(
                    escape(0),
                    suit(1, SkullSuit.YELLOW, 3),
                    escape(2),
                    suit(3, SkullSuit.YELLOW, 8));

            assertThat(winner(played)).isEqualTo(3);
        }

        @Test
        void escape_lead_then_single_suit_card_takes_it() {
            assertThat(winner(List.of(escape(0), suit(1, SkullSuit.PURPLE, 1), escape(2))))
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("§8 동점 — 먼저 낸 사람")
    class Ties {

        @Test
        void earliest_pirate_wins_among_pirates() {
            assertThat(winner(List.of(suit(0, SkullSuit.GREEN, 5), pirate(1), pirate(2))))
                    .isEqualTo(1);
        }

        @Test
        void earliest_mermaid_wins_among_mermaids() {
            assertThat(winner(List.of(mermaid(0), mermaid(1), suit(2, SkullSuit.BLACK, 14))))
                    .isZero();
        }

        /**
         * §13-① — 인어 2장 + 해적 + 스컬킹. 원문의 인어 동점 규칙은 "해적이 나오지
         * 않았고"로 한정돼 이 경우를 못 덮고, 3자 예외는 "인어를 낸 플레이어"라고 단수로만
         * 쓴다. 먼저 낸 인어로 확정했다.
         */
        @Test
        void two_mermaids_with_pirate_and_skull_king_yields_the_earlier_mermaid() {
            List<PlayedCard> played = List.of(
                    pirate(0), mermaid(1), skullKing(2), mermaid(3));

            assertThat(winner(played)).isEqualTo(1);
        }

        @Test
        void mermaid_order_is_what_decides_not_seat_number() {
            List<PlayedCard> played = List.of(
                    mermaid(3), skullKing(0), mermaid(1));

            assertThat(winner(played)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("티그리스 — 선언값이 곧 정체성 (§13-②③)")
    class Tigress {

        @Test
        void declared_as_pirate_beats_a_mermaid() {
            List<PlayedCard> played = List.of(
                    mermaid(0), PlayedCard.tigress(1, TigressMode.PIRATE));

            assertThat(winner(played)).isEqualTo(1);
        }

        @Test
        void declared_as_pirate_loses_to_skull_king() {
            List<PlayedCard> played = List.of(
                    PlayedCard.tigress(0, TigressMode.PIRATE), skullKing(1));

            assertThat(winner(played)).isEqualTo(1);
        }

        /**
         * §13-② — 진짜 해적이 0장이어도 해적 선언 티그리스가 3자 예외를 발동시킨다.
         * 미포함으로 읽으면 인어와 스컬킹만 남아 판정이 불능이 된다.
         */
        @Test
        void declared_as_pirate_triggers_the_three_way_exception_without_a_real_pirate() {
            List<PlayedCard> played = List.of(
                    skullKing(0), mermaid(1), PlayedCard.tigress(2, TigressMode.PIRATE));

            assertThat(winner(played))
                    .as("스컬킹+인어+(티그리스=해적) → 인어")
                    .isEqualTo(1);
        }

        /** §13-③ — 티그리스가 진짜 해적보다 먼저 나오면 티그리스를 낸 사람이 이긴다. */
        @Test
        void declared_as_pirate_counts_for_the_earliest_pirate_tie_break() {
            List<PlayedCard> played = List.of(
                    PlayedCard.tigress(0, TigressMode.PIRATE), pirate(1));

            assertThat(winner(played)).isZero();
        }

        @Test
        void declared_as_escape_always_loses() {
            List<PlayedCard> played = List.of(
                    suit(0, SkullSuit.GREEN, 2), PlayedCard.tigress(1, TigressMode.ESCAPE));

            assertThat(winner(played)).isZero();
        }

        /** §13-④ — "전원 탈출" 판정에 탈출 선언 티그리스도 포함된다. */
        @Test
        void declared_as_escape_participates_in_an_all_escape_trick() {
            List<PlayedCard> played = List.of(
                    PlayedCard.tigress(0, TigressMode.ESCAPE), escape(1));

            assertThat(winner(played))
                    .as("승자 없는 트릭이 생기지 않아야 한다")
                    .isZero();
        }

        @Test
        void declared_as_escape_can_be_beaten_by_a_later_escape_only_by_order() {
            assertThat(winner(List.of(escape(0), PlayedCard.tigress(1, TigressMode.ESCAPE))))
                    .isZero();
        }
    }

    @Nested
    @DisplayName("결과 객체")
    class Result {

        @Test
        void carries_the_winning_card_and_all_cards() {
            List<PlayedCard> played = List.of(
                    suit(0, SkullSuit.GREEN, 5), pirate(1), mermaid(2));

            TrickResult result = TrickResolver.resolve(played);

            assertThat(result.winnerSeat()).isEqualTo(1);
            assertThat(result.winningCard()).isEqualTo(pirate(1));
            assertThat(result.cards()).containsExactlyElementsOf(played);
        }

        @Test
        void defeated_excludes_only_the_winning_card() {
            List<PlayedCard> played = List.of(
                    suit(0, SkullSuit.GREEN, 5), pirate(1), mermaid(2));

            assertThat(TrickResolver.resolve(played).defeated())
                    .containsExactly(suit(0, SkullSuit.GREEN, 5), mermaid(2));
        }

        @Test
        void empty_trick_is_rejected() {
            assertThatThrownBy(() -> TrickResolver.resolve(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty trick");
        }
    }
}
