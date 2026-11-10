package com.mirboard.domain.game.yacht;

import static com.mirboard.domain.game.yacht.YachtFixtures.T0;
import static com.mirboard.domain.game.yacht.YachtFixtures.allBut;
import static com.mirboard.domain.game.yacht.YachtFixtures.openState;
import static com.mirboard.domain.game.yacht.YachtFixtures.withCard;
import static com.mirboard.domain.game.yacht.YachtFixtures.withTurn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEngine.DesertOutcome;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.yacht.action.YachtAction;
import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.event.YachtEvent;
import com.mirboard.domain.game.yacht.event.YachtMatchCompleted;
import com.mirboard.domain.game.yacht.persistence.YachtStateStore;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.state.YachtState;
import com.mirboard.domain.game.yacht.state.YachtStateMapper.PublicView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** S3 포트 어댑터 — 차례 전체 타이머(D-183)·뷰·봇 안전망·탈주·기록 발행. 저장소는 모의라 Docker 없이 돈다. */
class YachtGameEngineTest {

    private static final List<Long> PLAYERS = List.of(10L, 11L);

    private final YachtStateStore store = mock(YachtStateStore.class);
    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    private YachtGameEngine adapter(int turnSeconds, long nowMillis, ApplicationEventPublisher pub) {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC);
        return new YachtGameEngine(new GameContext("r1", PLAYERS, 0, 0, List.of(1), Map.of(), turnSeconds), store, clock,
                new Random(5), pub);
    }

    @Test
    void the_timer_counts_the_whole_turn_from_its_start() {
        YachtState s = withTurn(openState(2, 3, 0), Dice.of(1, 2, 3, 4, 5), HoldMask.NONE, 2); // 시작 T0
        assertThat(adapter(45, T0 + 30_000, publisher).timer(s)).contains(Duration.ofSeconds(15));
        assertThat(adapter(45, T0 + 60_000, publisher).timer(s)).contains(Duration.ZERO);
        assertThat(adapter(0, T0, publisher).timer(s)).isEmpty();
    }

    @Test
    void on_timer_scores_the_best_category_automatically() {
        YachtState s = withTurn(openState(2, 3, 0), Dice.of(6, 6, 6, 6, 6), HoldMask.NONE, 1);
        GameEngine.Result r = adapter(45, T0 + 45_000, publisher).onTimer(s).orElseThrow();
        assertThat(r.events()).first().isEqualTo(new YachtEvent.Scored(0, Category.YACHT, 50, 0, 0, 50, true));
        assertThat(((YachtState) r.newState()).turnSeat()).isEqualTo(1);
    }

    @Test
    void views_are_public_only_and_carry_the_engine_remaining_time() {
        YachtState s = openState(2, 1, 0);
        YachtGameEngine a = adapter(45, T0 + 5_000, publisher);
        assertThat(a.privateView(s, 0)).isEmpty();
        PublicView view = (PublicView) a.publicView(s);
        assertThat(view.turnRemainingMs()).isEqualTo(40_000L);
        assertThat(((PublicView) adapter(0, T0, publisher).publicView(s)).turnRemainingMs()).isNull();
        assertThat(a.pendingSeats(s)).containsExactly(0);
        assertThat(a.phaseName(s)).isEqualTo("PLAYING");
        assertThat(a.actionType()).isEqualTo(YachtAction.class);
    }

    @Test
    void apply_rolls_with_the_adapter_dice() {
        YachtState s = openState(2, 1, 0);
        GameEngine.Result r = adapter(0, T0, publisher).apply(s, 0, YachtAction.Roll.plain());
        assertThat(((YachtState) r.newState()).turn().rolls()).isEqualTo(1);
        assertThat(r.events()).singleElement().isInstanceOf(YachtEvent.DiceRolled.class);
    }

    @Test
    void the_bot_follows_its_policy_and_falls_back_when_the_policy_breaks() {
        // 2 2 3 3 3 — 풀 하우스 13 = 초이스 13 동점이라 폴백은 위 칸인 초이스
        YachtState s = withTurn(openState(2, 2, 1), Dice.of(2, 2, 3, 3, 3), HoldMask.NONE, 3);
        YachtGameEngine a = adapter(0, T0, publisher);

        assertThat(a.botAction(s, 1, new Random())).isInstanceOf(YachtAction.Score.class);
        assertThat(a.chooseBotAction(s, 1, v -> {
            throw new IllegalStateException("boom");
        })).isEqualTo(new YachtAction.Score(Category.CHOICE));
        assertThat(a.chooseBotAction(s, 1, v -> YachtAction.Roll.plain()))
                .as("3번 굴린 뒤의 ROLL 은 합법수가 아니다").isEqualTo(new YachtAction.Score(Category.CHOICE));
        assertThat(a.chooseBotAction(s, 1, v -> null)).isEqualTo(new YachtAction.Score(Category.CHOICE));
        assertThat(a.botAction(s, 0, new Random())).as("남의 차례").isNull();
    }

    @Test
    void the_timeout_action_is_the_deterministic_fallback() {
        YachtGameEngine a = adapter(45, T0, publisher);
        assertThat(a.timeoutAction(openState(2, 1, 0), 0)).isEqualTo(YachtAction.Roll.plain());
    }

    @Test
    void advance_publishes_the_record_event_once_when_the_match_ends() {
        YachtState s = withTurn(openState(2, 12, 1), Dice.of(6, 6, 6, 6, 6), HoldMask.NONE, 1);
        s = withCard(withCard(s, 0, allBut(null)), 1, allBut(Category.YACHT));
        YachtGameEngine a = adapter(0, T0, publisher);
        GameEngine.Result r = a.apply(s, 1, new YachtAction.Score(Category.YACHT));

        assertThat(a.advance(r.newState(), r.events())).isEqualTo(new GameEngine.Advance(true, true));
        assertThat(published).singleElement().isInstanceOf(YachtMatchCompleted.class);
        assertThat(a.advance(r.newState(), List.of())).as("이미 끝난 상태의 재호출").isEqualTo(GameEngine.Advance.NONE);
        assertThat(published).hasSize(1);
        assertThat(a.isRoundOver(r.newState())).isTrue();
    }

    @Test
    void a_recording_failure_does_not_escape_the_adapter() {
        YachtState s = withTurn(openState(2, 12, 1), Dice.of(6, 6, 6, 6, 6), HoldMask.NONE, 1);
        s = withCard(withCard(s, 0, allBut(null)), 1, allBut(Category.YACHT));
        AtomicInteger calls = new AtomicInteger();
        YachtGameEngine a = adapter(0, T0, event -> {
            calls.incrementAndGet();
            throw new IllegalStateException("db down");
        });
        GameEngine.Result r = a.apply(s, 1, new YachtAction.Score(Category.YACHT));
        assertThat(a.advance(r.newState(), r.events()).matchCompleted()).isTrue();
        assertThat(calls).hasValue(1);
    }

    @Test
    void desertion_maps_the_three_outcomes_and_saves_only_on_change() {
        YachtGameEngine a = adapter(0, T0, publisher);
        when(store.load("r1")).thenReturn(Optional.empty());
        assertThat(a.desert(0, 10L, new ArrayList<>())).isEqualTo(DesertOutcome.NOT_APPLICABLE);

        // 좌석 1 은 봇 — 사람(좌석 0)이 나가면 사람이 없어 끝난다
        when(store.load("r1")).thenReturn(Optional.of(openState(2, 2, 0)));
        List<GameEvent> out = new ArrayList<>();
        assertThat(a.desert(0, 10L, out)).isEqualTo(DesertOutcome.MATCH_ENDED);
        assertThat(out).last().isInstanceOf(YachtEvent.MatchEnded.class);
        assertThat(published).singleElement().isInstanceOf(YachtMatchCompleted.class);
        verify(store).save(eq("r1"), any());
    }

    @Test
    void a_bot_leaving_keeps_the_match_going() {
        YachtGameEngine a = adapter(0, T0, publisher);
        when(store.load("r1")).thenReturn(Optional.of(openState(2, 2, 0)));
        assertThat(a.desert(1, 11L, new ArrayList<>())).isEqualTo(DesertOutcome.MATCH_CONTINUES);
        assertThat(published).isEmpty();
    }

    @Test
    void a_deserted_seat_leaving_again_is_not_applicable() {
        YachtGameEngine a = adapter(0, T0, publisher);
        when(store.load("r1")).thenReturn(Optional.of(YachtFixtures.withDeserted(openState(2, 2, 0), 1)));
        assertThat(a.desert(1, 11L, new ArrayList<>())).isEqualTo(DesertOutcome.NOT_APPLICABLE);
        verify(store, never()).save(any(), any());
    }

    /** D-202 — 동시 진행에서는 봇의 홀드를 굴림에 실어 한 액션으로(차례대로는 그대로 홀드). */
    @Test
    void in_simultaneous_play_a_bot_hold_is_carried_into_the_roll() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(T0), ZoneOffset.UTC);
        YachtGameEngine simultaneous = new YachtGameEngine(new GameContext("r1", PLAYERS, 0, 0, List.of(1),
                Map.of(com.mirboard.domain.game.core.RoomOption.TURN_MODE, "SIMULTANEOUS"), 45), store, clock,
                new Random(5), publisher);
        YachtEngine rules = YachtFixtures.simultaneousEngine(2, 45, 1);
        YachtState s = rules.start(T0).newState();
        s = rules.apply(s, 1, YachtAction.Roll.plain(), () -> 2, T0).newState();
        HoldMask mask = HoldMask.of(true, false, false, false, false);
        assertThat(simultaneous.chooseBotAction(s, 1, v -> new YachtAction.Hold(mask)))
                .isEqualTo(new YachtAction.Roll(mask));
        assertThat(simultaneous.pendingSeats(s)).containsExactly(0, 1);
        assertThat(simultaneous.timeoutAction(s, 1)).isEqualTo(new YachtAction.Score(Category.YACHT));
    }
}
