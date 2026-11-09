package com.mirboard.domain.game.yacht.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.score.Scorecard;
import com.mirboard.domain.game.yacht.state.MatchResult;
import com.mirboard.domain.game.yacht.state.MatchResult.EndReason;
import com.mirboard.domain.game.yacht.state.TurnState;
import com.mirboard.domain.game.yacht.state.YachtState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** `docs/rules-yacht.md` §8.2. */
class RankingTest {

    private static Scorecard total(int points) {
        return Scorecard.EMPTY.with(Category.CHOICE, points);
    }

    private static YachtState state(List<Scorecard> cards, Integer... deserted) {
        return new YachtState(cards, 12, -1, TurnState.none(), List.of(deserted), 0, null);
    }

    private static List<String> ranks(YachtState s) {
        return Ranking.placements(s).stream().map(p -> p.seat() + ":" + p.rank()).toList();
    }

    @Test
    void totals_descend_with_competition_ties() {
        YachtState s = state(List.of(total(20), total(25), total(20), total(10)));
        assertThat(ranks(s)).containsExactly("1:1", "0:2", "2:2", "3:4");
    }

    @Test
    void a_three_way_tie_shares_first() {
        YachtState s = state(List.of(total(20), total(20), total(20)));
        MatchResult r = Ranking.result(s, EndReason.FINISHED);
        assertThat(ranks(s)).containsExactly("0:1", "1:1", "2:1");
        assertThat(r.winners()).containsExactly(0, 1, 2);
    }

    @Test
    void deserters_share_last_place_whatever_their_score() {
        YachtState s = state(List.of(total(5), total(30), total(10), total(1)), 1, 3);
        assertThat(Ranking.placements(s)).extracting(p -> p.seat() + ":" + p.rank() + ":" + p.deserted())
                .containsExactly("2:1:false", "0:2:false", "1:3:true", "3:3:true");
    }

    @Test
    void solo_is_first() {
        assertThat(ranks(state(List.of(total(0))))).containsExactly("0:1");
    }

    @Test
    void bonus_counts_in_the_total() {
        Scorecard bonus = Scorecard.EMPTY.with(Category.SIXES, 30).with(Category.FIVES, 25).with(Category.FOURS, 8);
        YachtState s = state(List.of(total(97), bonus));
        assertThat(ranks(s)).containsExactly("1:1", "0:2");
    }
}
