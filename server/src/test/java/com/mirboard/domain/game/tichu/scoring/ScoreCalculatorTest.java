package com.mirboard.domain.game.tichu.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuDeclaration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoreCalculatorTest {

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    /** 모든 카드 포인트 합은 항상 100 (Dragon +25 - Phoenix 25 + 4*(5+10+10)). */
    @Test
    void normal_end_distributes_tricks_with_loser_giving_to_first_finisher_and_opposing_team() {
        // seat 0 (Team A) finishes 1st with 30 trick points
        // seat 1 (Team B) finishes 2nd with 25 trick points
        // seat 2 (Team A) finishes 3rd with 20 trick points
        // seat 3 (Team B) loser, 25 trick points + 0 remaining in hand
        var p0 = finishedAt(0, 1, List.of(n(Suit.JADE, 5), n(Suit.SWORD, 5), n(Suit.JADE, 10), n(Suit.JADE, 10))); // 5+5+10+10=30
        var p1 = finishedAt(1, 2, List.of(n(Suit.JADE, 5), n(Suit.SWORD, 10), n(Suit.STAR, 10))); // 25
        var p2 = finishedAt(2, 3, List.of(n(Suit.PAGODA, 10), n(Suit.STAR, 5), n(Suit.STAR, 5))); // 20
        var p3 = stillPlaying(3, List.of(n(Suit.PAGODA, 5), n(Suit.STAR, 10), n(Suit.JADE, 13)), List.of()); // hand 5+10+10=25 → opposing team A

        var score = ScoreCalculator.compute(List.of(p0, p1, p2, p3));

        // Team A: p0(30) + p2(20) + loser tricks 0 (p3 has none) + loser hand 25
        // Team B: p1(25) + 0
        assertThat(score.teamAScore()).isEqualTo(30 + 20 + 0 + 25);
        assertThat(score.teamBScore()).isEqualTo(25);
        assertThat(score.firstFinisherSeat()).isZero();
        assertThat(score.doubleVictory()).isFalse();
    }

    @Test
    void loser_tricks_go_to_first_finisher_team() {
        var p0 = finishedAt(0, 1, List.of()); // Team A 1st, no tricks
        var p1 = finishedAt(1, 2, List.of()); // Team B
        var p2 = finishedAt(2, 3, List.of()); // Team A
        // loser has 50 trick points → first finisher (seat 0, Team A) gets them
        var p3 = stillPlaying(3,
                List.of(),
                List.of(n(Suit.JADE, 5), n(Suit.SWORD, 5), n(Suit.JADE, 10), n(Suit.SWORD, 10), n(Suit.STAR, 10)));

        var score = ScoreCalculator.compute(List.of(p0, p1, p2, p3));

        assertThat(score.teamAScore()).isEqualTo(50);  // all loser tricks → seat 0's team
        assertThat(score.teamBScore()).isZero();
    }

    @Test
    void double_victory_grants_two_hundred_and_skips_trick_math() {
        var p0 = finishedAt(0, 1, List.of(n(Suit.JADE, 5), n(Suit.SWORD, 10))); // 15 — ignored under double victory
        var p1 = stillPlaying(1, List.of(n(Suit.SWORD, 5), n(Suit.JADE, 10)), List.of());
        var p2 = finishedAt(2, 2, List.of(n(Suit.STAR, 10))); // partner of p0, finishes 2nd
        var p3 = stillPlaying(3, List.of(n(Suit.STAR, 5), n(Suit.PAGODA, 10)), List.of());

        var score = ScoreCalculator.compute(List.of(p0, p1, p2, p3));

        assertThat(score.doubleVictory()).isTrue();
        assertThat(score.teamAScore()).isEqualTo(200);
        assertThat(score.teamBScore()).isZero();
    }

    @Test
    void double_victory_still_applies_tichu_bonus() {
        var p0 = finishedAt(0, 1, List.of()).withDeclaration(TichuDeclaration.TICHU);
        var p1 = stillPlaying(1, List.of(), List.of());
        var p2 = finishedAt(2, 2, List.of());
        var p3 = stillPlaying(3, List.of(), List.of());

        var score = ScoreCalculator.compute(List.of(p0, p1, p2, p3));

        // Team A: double victory 200 + Tichu success +100 = 300
        assertThat(score.teamAScore()).isEqualTo(300);
        assertThat(score.teamBScore()).isZero();
    }

    @Test
    void successful_tichu_adds_one_hundred() {
        var p0 = finishedAt(0, 1, List.of()).withDeclaration(TichuDeclaration.TICHU);
        var p1 = finishedAt(1, 2, List.of());
        var p2 = finishedAt(2, 3, List.of());
        var p3 = stillPlaying(3, List.of(), List.of());

        var score = ScoreCalculator.compute(List.of(p0, p1, p2, p3));

        // No tricks. Loser has no hand cards. Just Tichu bonus +100.
        assertThat(score.teamAScore()).isEqualTo(100);
        assertThat(score.teamBScore()).isZero();
    }

    @Test
    void failed_tichu_subtracts_one_hundred() {
        // p0 declared Tichu but finished 2nd
        var p0 = finishedAt(0, 2, List.of()).withDeclaration(TichuDeclaration.TICHU);
        var p1 = finishedAt(1, 1, List.of());
        var p2 = finishedAt(2, 3, List.of());
        var p3 = stillPlaying(3, List.of(), List.of());

        var score = ScoreCalculator.compute(List.of(p0, p1, p2, p3));

        assertThat(score.teamAScore()).isEqualTo(-100);
        assertThat(score.teamBScore()).isZero();
    }

    @Test
    void successful_grand_tichu_adds_two_hundred() {
        var p0 = finishedAt(0, 1, List.of()).withDeclaration(TichuDeclaration.GRAND_TICHU);
        var p1 = finishedAt(1, 2, List.of());
        var p2 = finishedAt(2, 3, List.of());
        var p3 = stillPlaying(3, List.of(), List.of());

        var score = ScoreCalculator.compute(List.of(p0, p1, p2, p3));

        assertThat(score.teamAScore()).isEqualTo(200);
        assertThat(score.teamBScore()).isZero();
    }

    @Test
    void failed_grand_tichu_subtracts_two_hundred() {
        // p0 declared Grand Tichu but finished 3rd (not 1st)
        var p0 = finishedAt(0, 3, List.of()).withDeclaration(TichuDeclaration.GRAND_TICHU);
        var p1 = finishedAt(1, 1, List.of());
        var p2 = finishedAt(2, 2, List.of());
        var p3 = stillPlaying(3, List.of(), List.of());

        var score = ScoreCalculator.compute(List.of(p0, p1, p2, p3));

        assertThat(score.teamAScore()).isEqualTo(-200);
    }

    @Test
    void dragon_in_trick_pile_adds_twenty_five() {
        var p0 = finishedAt(0, 1, List.of(Card.dragon())); // Team A wins +25
        var p1 = finishedAt(1, 2, List.of());
        var p2 = finishedAt(2, 3, List.of());
        var p3 = stillPlaying(3, List.of(), List.of());

        var score = ScoreCalculator.compute(List.of(p0, p1, p2, p3));

        assertThat(score.teamAScore()).isEqualTo(25);
    }

    @Test
    void phoenix_in_trick_pile_subtracts_twenty_five() {
        var p0 = finishedAt(0, 1, List.of(Card.phoenix())); // Phoenix=-25 to owner team
        var p1 = finishedAt(1, 2, List.of());
        var p2 = finishedAt(2, 3, List.of());
        var p3 = stillPlaying(3, List.of(), List.of());

        var score = ScoreCalculator.compute(List.of(p0, p1, p2, p3));

        assertThat(score.teamAScore()).isEqualTo(-25);
    }

    @Test
    void all_card_points_sum_is_one_hundred() {
        // Sanity: distribute all point-bearing cards. p3 holds nothing → all to p0..p2.
        // 4×(5+10+10) = 100, Dragon +25 - Phoenix 25 = 0. Total = 100.
        var p0 = finishedAt(0, 1, List.of(
                n(Suit.JADE, 5), n(Suit.SWORD, 5), n(Suit.JADE, 10), n(Suit.SWORD, 10), n(Suit.JADE, 13), n(Suit.SWORD, 13)));
        var p1 = finishedAt(1, 2, List.of(
                n(Suit.PAGODA, 5), n(Suit.STAR, 5), n(Suit.PAGODA, 10), n(Suit.STAR, 10),
                n(Suit.PAGODA, 13), n(Suit.STAR, 13)));
        var p2 = finishedAt(2, 3, List.of(Card.dragon(), Card.phoenix()));
        var p3 = stillPlaying(3, List.of(), List.of());

        var score = ScoreCalculator.compute(List.of(p0, p1, p2, p3));

        assertThat(score.teamAScore() + score.teamBScore()).isEqualTo(100);
    }

    @Test
    void scoring_before_anyone_finishes_throws() {
        var p0 = stillPlaying(0, List.of(), List.of());
        var p1 = stillPlaying(1, List.of(), List.of());
        var p2 = stillPlaying(2, List.of(), List.of());
        var p3 = stillPlaying(3, List.of(), List.of());

        assertThatThrownBy(() -> ScoreCalculator.compute(List.of(p0, p1, p2, p3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scoring_with_only_two_finished_but_no_double_victory_throws() {
        // Two finished but from opposing teams → not double victory, not yet 3-finished.
        var p0 = finishedAt(0, 1, List.of()); // Team A
        var p1 = finishedAt(1, 2, List.of()); // Team B
        var p2 = stillPlaying(2, List.of(), List.of());
        var p3 = stillPlaying(3, List.of(), List.of());

        assertThatThrownBy(() -> ScoreCalculator.compute(List.of(p0, p1, p2, p3)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- helpers ----------

    private static PlayerState finishedAt(int seat, int finishedOrder, List<Card> tricksWon) {
        return new PlayerState(seat, List.of(), TichuDeclaration.NONE, finishedOrder, tricksWon);
    }

    private static PlayerState stillPlaying(int seat, List<Card> tricksWon, List<Card> hand) {
        return new PlayerState(seat, hand, TichuDeclaration.NONE, -1, tricksWon);
    }
}
