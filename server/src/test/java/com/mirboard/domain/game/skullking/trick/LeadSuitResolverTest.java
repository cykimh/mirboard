package com.mirboard.domain.game.skullking.trick;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 리드 수트 지연 확정 (§6.1, §13-⑤). */
class LeadSuitResolverTest {

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

    @Test
    void empty_trick_has_no_lead_suit() {
        assertThat(LeadSuitResolver.resolve(List.of())).isEmpty();
    }

    @Nested
    @DisplayName("색상 카드로 리드 — 즉시 확정")
    class SuitLead {

        @Test
        void first_suit_card_fixes_the_lead_suit() {
            assertThat(LeadSuitResolver.resolve(List.of(suit(0, SkullSuit.GREEN, 5))))
                    .contains(SkullSuit.GREEN);
        }

        @Test
        void later_cards_do_not_change_it() {
            List<PlayedCard> played = List.of(
                    suit(0, SkullSuit.GREEN, 5),
                    suit(1, SkullSuit.YELLOW, 14),
                    suit(2, SkullSuit.BLACK, 2));

            assertThat(LeadSuitResolver.resolve(played)).contains(SkullSuit.GREEN);
        }

        @Test
        void black_lead_is_just_a_normal_lead_suit() {
            assertThat(LeadSuitResolver.resolve(List.of(suit(0, SkullSuit.BLACK, 3))))
                    .contains(SkullSuit.BLACK);
        }
    }

    @Nested
    @DisplayName("캐릭터로 리드 — 영구히 리드 수트 없음")
    class CharacterLead {

        @Test
        void pirate_lead_leaves_no_lead_suit_even_after_a_suit_card() {
            List<PlayedCard> played = List.of(pirate(0), suit(1, SkullSuit.GREEN, 5));

            assertThat(LeadSuitResolver.resolve(played))
                    .as("캐릭터 리드는 이후 색상 카드가 나와도 리드 수트를 만들지 않는다")
                    .isEmpty();
        }

        @Test
        void mermaid_lead_leaves_no_lead_suit() {
            assertThat(LeadSuitResolver.resolve(List.of(mermaid(0), suit(1, SkullSuit.YELLOW, 9))))
                    .isEmpty();
        }

        @Test
        void skull_king_lead_leaves_no_lead_suit() {
            assertThat(LeadSuitResolver.resolve(List.of(skullKing(0), suit(1, SkullSuit.BLACK, 9))))
                    .isEmpty();
        }

        @Test
        void tigress_declared_as_pirate_is_a_character_lead() {
            List<PlayedCard> played = List.of(
                    PlayedCard.tigress(0, TigressMode.PIRATE),
                    suit(1, SkullSuit.GREEN, 5));

            assertThat(LeadSuitResolver.resolve(played)).isEmpty();
        }
    }

    @Nested
    @DisplayName("탈출로 리드 — 확정 보류")
    class EscapeLead {

        @Test
        void escape_alone_leaves_it_unresolved() {
            assertThat(LeadSuitResolver.resolve(List.of(escape(0)))).isEmpty();
        }

        @Test
        void next_suit_card_fixes_it() {
            List<PlayedCard> played = List.of(escape(0), suit(1, SkullSuit.PURPLE, 7));

            assertThat(LeadSuitResolver.resolve(played)).contains(SkullSuit.PURPLE);
        }

        @Test
        void consecutive_escapes_keep_deferring() {
            List<PlayedCard> played = List.of(
                    escape(0), escape(1), escape(2), suit(3, SkullSuit.YELLOW, 2));

            assertThat(LeadSuitResolver.resolve(played)).contains(SkullSuit.YELLOW);
        }

        @Test
        void tigress_declared_as_escape_defers_like_an_escape() {
            List<PlayedCard> played = List.of(
                    PlayedCard.tigress(0, TigressMode.ESCAPE),
                    suit(1, SkullSuit.GREEN, 4));

            assertThat(LeadSuitResolver.resolve(played)).contains(SkullSuit.GREEN);
        }

        /**
         * §13-⑤ 의 그 케이스 — 원문이 답하지 않는 "탈출 리드 → 캐릭터 → 색상". 확정 사건을
         * "색상 카드 제출"에 걸어 둔 원문 문면대로, 캐릭터가 끼어도 미확정으로 남았다가
         * 그 뒤 색상 카드가 리드 수트를 만든다.
         */
        @Test
        void escape_then_character_then_suit_resolves_to_that_suit() {
            List<PlayedCard> played = List.of(
                    escape(0), pirate(1), suit(2, SkullSuit.GREEN, 6));

            assertThat(LeadSuitResolver.resolve(played)).contains(SkullSuit.GREEN);
        }

        @Test
        void escape_then_character_only_stays_unresolved() {
            assertThat(LeadSuitResolver.resolve(List.of(escape(0), pirate(1)))).isEmpty();
        }

        @Test
        void all_escapes_never_resolve() {
            assertThat(LeadSuitResolver.resolve(List.of(escape(0), escape(1), escape(2))))
                    .isEqualTo(Optional.empty());
        }
    }
}
