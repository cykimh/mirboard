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
        assertThat(PlayRules.canPlay(BLACK, null, 5, card(Suit.HEART, 2))).isFalse();
        assertThat(PlayRules.canPlay(COLOR, null, 7, BLACK)).isFalse();
        assertThat(PlayRules.canPlay(COLOR, null, 7, card(Suit.HEART, 2))).isFalse();
        assertThat(PlayRules.canPlay(COLOR, null, 7, card(Suit.HEART, PlayingCard.ACE))).isFalse();
    }

    @Test
    void under_attack_normal_and_special_cards_cannot_be_played() {
        PlayingCard top = card(Suit.HEART, 2);
        assertThat(underAttack(top, card(Suit.HEART, 5))).isFalse();
        assertThat(underAttack(top, card(Suit.HEART, 7))).isFalse();
        assertThat(underAttack(top, card(Suit.HEART, PlayingCard.KING))).isFalse();
    }
}
