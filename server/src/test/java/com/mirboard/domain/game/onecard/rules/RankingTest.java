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
