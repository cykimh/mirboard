package com.mirboard.domain.game.yacht;

import static com.mirboard.domain.game.yacht.YachtFixtures.T0;
import static com.mirboard.domain.game.yacht.YachtFixtures.simultaneousEngine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.yacht.action.RejectionReason;
import com.mirboard.domain.game.yacht.action.YachtAction;
import com.mirboard.domain.game.yacht.action.YachtActionRejectedException;
import com.mirboard.domain.game.yacht.bot.YachtBotPolicy;
import com.mirboard.domain.game.yacht.bot.YachtBotView;
import com.mirboard.domain.game.yacht.dice.DiceRoller;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.event.YachtEvent;
import com.mirboard.domain.game.yacht.invariant.YachtInvariantChecker;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.state.MatchResult.EndReason;
import com.mirboard.domain.game.yacht.state.YachtState;
import com.mirboard.domain.game.yacht.state.YachtStateMapper;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** D-202 — 동시 진행: 라운드마다 모두 같이 굴리고, 전원이 기록하면 다음 라운드. 제한은 라운드 전체 하나. */
class YachtEngineSimultaneousTest {

    private static final YachtAction ROLL = YachtAction.Roll.plain();
    private final DiceRoller ones = () -> 1;

    @Test
    void the_first_round_opens_for_everyone_at_once() {
        YachtEngine engine = simultaneousEngine(3, 45);
        YachtEngine.Result started = engine.start(T0);
        YachtState s = started.newState();
        assertThat(engine.simultaneous()).isTrue();
        assertThat(s.simultaneous()).isTrue();
        assertThat(s.turnSeat()).isEqualTo(-1);
        assertThat(engine.pendingSeats(s)).containsExactly(0, 1, 2);
        assertThat(started.events()).containsExactly(new YachtEvent.RoundStarted(1, 45));
        YachtInvariantChecker.check(s, Set.of());
    }

    @Test
    void each_seat_rolls_on_its_own_dice_and_the_round_turns_when_the_last_one_scores() {
        YachtEngine engine = simultaneousEngine(2, 0);
        YachtState s = engine.start(T0).newState();

        s = engine.apply(s, 1, ROLL, ones, T0 + 1).newState();
        assertThat(s.turnOf(1).rolls()).isEqualTo(1);
        assertThat(s.turnOf(0).rolls()).as("남의 주사위는 그대로").isZero();
        s = engine.apply(s, 0, ROLL, () -> 6, T0 + 2).newState();
        s = engine.apply(s, 0, new YachtAction.Hold(HoldMask.of(true, true, false, false, false)), ones, T0 + 3)
                .newState();
        assertThat(s.turnOf(0).held()).isEqualTo(HoldMask.of(true, true, false, false, false));

        YachtEngine.Result first = engine.apply(s, 1, new YachtAction.Score(Category.ONES), ones, T0 + 4);
        s = first.newState();
        assertThat(s.round()).as("한 명이 남아 라운드는 그대로").isEqualTo(1);
        assertThat(engine.pendingSeats(s)).containsExactly(0);
        assertThat(first.events()).noneMatch(YachtEvent.RoundStarted.class::isInstance);
        YachtState afterFirst = s;
        assertThatThrownBy(() -> engine.apply(afterFirst, 1, ROLL, ones, T0 + 5))
                .isInstanceOfSatisfying(YachtActionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.NOT_YOUR_TURN));
        YachtInvariantChecker.check(s, Set.of());

        YachtEngine.Result second = engine.apply(s, 0, new YachtAction.Score(Category.SIXES), ones, T0 + 6);
        s = second.newState();
        assertThat(second.events()).last().isEqualTo(new YachtEvent.RoundStarted(2, 0));
        assertThat(s.round()).isEqualTo(2);
        assertThat(engine.pendingSeats(s)).containsExactly(0, 1);
        assertThat(s.turnOf(0).rolls()).isZero();
        assertThat(s.turn().startedAt()).as("라운드 시계").isEqualTo(T0 + 6);
        YachtInvariantChecker.check(s, Set.of());
    }

    @Test
    void the_round_deadline_auto_scores_everyone_still_playing() {
        YachtEngine engine = simultaneousEngine(3, 30);
        YachtState s = engine.start(T0).newState();
        s = engine.apply(s, 2, ROLL, ones, T0 + 1).newState();
        s = engine.apply(s, 2, new YachtAction.Score(Category.ONES), ones, T0 + 2).newState();
        assertThat(engine.remainingMillis(s, T0 + 10_000)).hasValue(20_000);

        YachtEngine.Result expired = engine.expire(s, ones, T0 + 30_000);
        YachtState next = expired.newState();
        assertThat(expired.events()).filteredOn(YachtEvent.Scored.class::isInstance)
                .extracting(e -> ((YachtEvent.Scored) e).seat()).containsExactly(0, 1);
        assertThat(expired.events()).filteredOn(YachtEvent.Scored.class::isInstance)
                .allMatch(e -> ((YachtEvent.Scored) e).auto());
        assertThat(expired.events()).last().isEqualTo(new YachtEvent.RoundStarted(2, 30));
        assertThat(next.round()).isEqualTo(2);
        assertThat(next.card(0).filledCount()).isEqualTo(1);
        assertThat(next.card(1).filledCount()).isEqualTo(1);
        YachtInvariantChecker.check(next, Set.of());
    }

    @Test
    void a_deserter_who_was_the_last_one_playing_opens_the_next_round() {
        YachtEngine engine = simultaneousEngine(3, 0);
        YachtState s = engine.start(T0).newState();
        s = engine.apply(s, 0, ROLL, ones, T0).newState();
        s = engine.apply(s, 0, new YachtAction.Score(Category.ONES), ones, T0).newState();
        s = engine.apply(s, 1, ROLL, ones, T0).newState();
        s = engine.apply(s, 1, new YachtAction.Score(Category.ONES), ones, T0).newState();

        YachtEngine.Desertion d = engine.desert(s, 2, T0 + 9);
        assertThat(d.outcome()).isEqualTo(YachtEngine.Desertion.Outcome.MATCH_CONTINUES);
        assertThat(d.events()).first().isEqualTo(new YachtEvent.PlayerEliminated(2, 0));
        assertThat(d.events()).last().isEqualTo(new YachtEvent.RoundStarted(2, 0));
        assertThat(engine.pendingSeats(d.newState())).containsExactly(0, 1);
        YachtInvariantChecker.check(d.newState(), Set.of());
    }

    @Test
    void a_deserter_mid_round_just_leaves_and_the_others_keep_playing() {
        YachtEngine engine = simultaneousEngine(3, 0);
        YachtState s = engine.start(T0).newState();
        s = engine.apply(s, 1, ROLL, ones, T0).newState();
        YachtEngine.Desertion d = engine.desert(s, 1, T0);
        assertThat(d.events()).containsExactly(new YachtEvent.PlayerEliminated(1, 0));
        assertThat(engine.pendingSeats(d.newState())).containsExactly(0, 2);
        assertThat(d.newState().turnOf(1).dice()).isNull();
        YachtInvariantChecker.check(d.newState(), Set.of());
    }

    @Test
    void when_only_bots_remain_the_match_ends_with_no_humans() {
        YachtEngine engine = simultaneousEngine(2, 0, 1);
        YachtState s = engine.start(T0).newState();
        YachtEngine.Desertion d = engine.desert(s, 0, T0);
        assertThat(d.outcome()).isEqualTo(YachtEngine.Desertion.Outcome.MATCH_ENDED);
        assertThat(d.newState().result().reason()).isEqualTo(EndReason.NO_HUMANS);
        YachtInvariantChecker.check(d.newState(), Set.of(1));
    }

    @Test
    void a_solo_room_stays_sequential_even_if_simultaneous_was_chosen() {
        YachtEngine engine = simultaneousEngine(1, 0);
        YachtState s = engine.start(T0).newState();
        assertThat(engine.simultaneous()).isFalse();
        assertThat(s.simultaneous()).isFalse();
        assertThat(s.turnSeat()).isZero();
    }

    @Test
    void the_public_view_shows_every_seats_dice_and_who_is_still_playing() {
        YachtEngine engine = simultaneousEngine(2, 0);
        YachtState s = engine.start(T0).newState();
        s = engine.apply(s, 0, ROLL, ones, T0).newState();
        s = engine.apply(s, 1, ROLL, ones, T0).newState();
        s = engine.apply(s, 1, new YachtAction.Score(Category.ONES), ones, T0).newState();

        YachtStateMapper.PublicView view = YachtStateMapper.toPublicView(s, null);
        assertThat(view.simultaneous()).isTrue();
        assertThat(view.turnSeat()).isEqualTo(-1);
        assertThat(view.dice()).isNull();
        assertThat(view.candidates()).isEmpty();
        assertThat(view.turns()).hasSize(2);
        assertThat(view.turns().get(0).pending()).isTrue();
        assertThat(view.turns().get(0).rolls()).isEqualTo(1);
        assertThat(view.turns().get(0).candidates()).containsEntry(Category.ONES, 5);
        assertThat(view.turns().get(1).pending()).isFalse();
        assertThat(view.turns().get(1).dice()).isNull();
    }

    /** 봇 정책으로 2~4인 동시 진행 판을 끝까지 — 매 전이 불변식, 모두 12칸, 라운드 시작 알림은 11번. */
    @Test
    void whole_simultaneous_matches_reach_the_end_with_every_card_full() {
        Random pick = new Random(7);
        for (int seats = 2; seats <= 4; seats++) {
            for (int game = 0; game < 200; game++) {
                YachtEngine engine = simultaneousEngine(seats, 0);
                DiceRoller roller = DiceRoller.from(new Random(seats * 1000L + game));
                YachtState s = engine.start(T0).newState();
                int roundStarts = 0;
                while (!s.ended()) {
                    List<Integer> pending = engine.pendingSeats(s);
                    int seat = pending.get(pick.nextInt(pending.size()));
                    YachtAction action = YachtBotPolicy.choose(YachtBotView.of(s, seat));
                    YachtEngine.Result r = engine.apply(s, seat, action, roller, T0);
                    roundStarts += (int) r.events().stream().filter(YachtEvent.RoundStarted.class::isInstance).count();
                    s = r.newState();
                    YachtInvariantChecker.check(s, Set.of());
                }
                assertThat(s.result().reason()).isEqualTo(EndReason.FINISHED);
                YachtState ended = s;
                assertThat(ended.cards()).allMatch(c -> c.isFull());
                assertThat(roundStarts).isEqualTo(YachtState.ROUNDS - 1);
            }
        }
    }
}
