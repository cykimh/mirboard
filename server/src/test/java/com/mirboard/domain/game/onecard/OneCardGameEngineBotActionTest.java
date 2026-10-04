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
import com.mirboard.domain.game.onecard.action.OneCardAction.Draw;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.bot.OneCardBotPolicy;
import com.mirboard.domain.game.onecard.bot.OneCardBotView;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * D-128 — 어댑터의 봇 배선. 봇은 휴리스틱을 타고(난수 무시), 정책이 실패해도 먹기로 떨어져 방이 멈추지
 * 않으며, 경쟁 창·남의 차례에는 두지 않는다. 저장소가 필요 없는 경로라 Docker 없이 돈다.
 */
class OneCardGameEngineBotActionTest {

    private static final GameContext CONTEXT = new GameContext("bot-adapter", List.of(10L, 11L, 12L));

    private final OneCardGameEngine adapter = new OneCardGameEngine(CONTEXT, null, Clock.systemUTC(),
            new Random(3), RaceSettings.DEFAULT, event -> { });
    private final OneCardEngine rules = new OneCardEngine(CONTEXT, new Random(3));

    private static OneCardState onTurn() {
        return seats(hand(heart(9), club(3), spade(5)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();
    }

    @Test
    void the_bot_follows_the_heuristic_whatever_the_random() {
        OneCardState state = onTurn();
        OneCardAction expected = OneCardBotPolicy.choose(OneCardBotView.of(state, 0), rules.legalActions(state, 0));

        for (long seed : new long[] {1L, 2L, 3L}) {
            assertThat(adapter.botAction(state, 0, new Random(seed))).as("Random(%d)", seed).isEqualTo(expected);
        }
    }

    @Test
    void a_failing_or_illegal_policy_falls_back_to_drawing() {
        OneCardState state = onTurn();

        assertThat(adapter.chooseBotAction(state, 0, (view, legal) -> {
            throw new IllegalStateException("boom");
        })).isEqualTo(new Draw());
        assertThat(adapter.chooseBotAction(state, 0, (view, legal) -> null)).isEqualTo(new Draw());
        assertThat(adapter.chooseBotAction(state, 0, (view, legal) -> PlayCard.of(spade(4)))).isEqualTo(new Draw());
    }

    @Test
    void the_bot_does_not_move_during_a_race_or_out_of_turn() {
        OneCardState state = seats(hand(heart(9), club(3)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();
        OneCardState raced = rules.apply(state, 0, PlayCard.of(heart(9)), 0).newState();

        assertThat(adapter.botAction(raced, 0, new Random(1))).isNull();
        assertThat(adapter.botAction(raced, 1, new Random(1))).isNull();
        assertThat(adapter.botAction(onTurn(), 2, new Random(1))).isNull();
    }
}
