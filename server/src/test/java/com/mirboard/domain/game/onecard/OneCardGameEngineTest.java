package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceOutcome;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceResolved;
import com.mirboard.domain.game.onecard.event.OneCardMatchCompleted;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper.PrivateView;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper.TableView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * D-128 — 포트 어댑터. 시계를 넣는 자리(경쟁 창 여는 시각·엔진 타이머의 남은 시간), 매치 종료 기록 발행,
 * 탈주 3치 매핑. 저장소는 모의 객체라 Docker 없이 돈다.
 */
class OneCardGameEngineTest {

    private static final long NOW = 50_000L;
    /** 봇 반응 고정 — 주인 1.2초, 잡는 쪽 1.5초. */
    private static final RaceSettings FIXED = new RaceSettings(3_000, 1_200, 1_200, 1_500, 1_500, 8);

    private final OneCardStateStore store = mock(OneCardStateStore.class);
    private final List<Object> published = new ArrayList<>();

    private OneCardGameEngine engine(long now, int seatCount, Integer... botSeats) {
        List<Long> ids = LongStream.range(0, seatCount).map(i -> 100 + i).boxed().toList();
        return new OneCardGameEngine(new GameContext("room-1", ids, 0, 0, List.of(botSeats)), store,
                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), new Random(5), FIXED, published::add);
    }

    /** 좌석 0 이 두 장 중 ♥9 를 내 1장이 되는 3인 테이블. */
    private static OneCardState aboutToGoDownToOne() {
        return seats(hand(heart(9), club(3)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();
    }

    private static OneCardState raced(OneCardGameEngine engine) {
        return (OneCardState) engine.apply(aboutToGoDownToOne(), 0, PlayCard.of(heart(9))).newState();
    }

    @Test
    void a_race_opens_at_the_clock_time() {
        GameEngine.Result result = engine(NOW, 3).apply(aboutToGoDownToOne(), 0, PlayCard.of(heart(9)));

        OneCardState next = (OneCardState) result.newState();
        assertThat(next.race().openedAt()).isEqualTo(NOW);
        assertThat(result.events()).last().isInstanceOf(OneCardEvent.RaceOpened.class);
    }

    @Test
    void the_timer_is_the_time_left_until_the_window_ends_or_the_fastest_bot_presses() {
        OneCardState noBots = raced(engine(NOW, 3));
        OneCardState withCatcherBot = raced(engine(NOW, 3, 2));

        assertThat(engine(NOW + 1_000, 3).timer(noBots)).contains(Duration.ofMillis(2_000));
        assertThat(engine(NOW + 9_000, 3).timer(noBots)).contains(Duration.ZERO);
        assertThat(engine(NOW + 1_000, 3, 2).timer(withCatcherBot)).contains(Duration.ofMillis(500));
        assertThat(engine(NOW, 3).timer(aboutToGoDownToOne())).isEmpty();
    }

    @Test
    void the_timer_closes_the_race() {
        OneCardState raced = raced(engine(NOW, 3));

        Optional<GameEngine.Result> fired = engine(NOW + 3_000, 3).onTimer(raced);

        assertThat(fired).isPresent();
        assertThat(fired.get().events().getFirst())
                .isEqualTo(new RaceResolved(raced.race().raceId(), RaceOutcome.EXPIRED, -1));
        assertThat(engine(NOW, 3).onTimer(aboutToGoDownToOne())).isEmpty();
    }

    @Test
    void the_transition_that_ends_the_match_is_recorded_exactly_once() {
        OneCardState lastCard = seats(hand(heart(9)), hand(spade(4), spade(6)), hand(diamond(4), diamond(6)))
                .top(heart(5)).turn(0).build();
        OneCardGameEngine adapter = engine(NOW, 3);
        GameEngine.Result result = adapter.apply(lastCard, 0, PlayCard.of(heart(9)));

        GameEngine.Advance advance = adapter.advance(result.newState(), new ArrayList<>(result.events()));

        assertThat(advance).isEqualTo(new GameEngine.Advance(true, true));
        assertThat(published).singleElement().isInstanceOfSatisfying(OneCardMatchCompleted.class, done -> {
            assertThat(done.roomId()).isEqualTo("room-1");
            assertThat(done.playerIds()).containsExactly(100L, 101L, 102L);
            assertThat(done.result().reason()).isEqualTo(EndReason.FINISHED);
            assertThat(done.result().winners()).containsExactly(0);
        });
        assertThat(adapter.advance(result.newState(), new ArrayList<>())).isEqualTo(GameEngine.Advance.NONE);
        assertThat(published).hasSize(1);
    }

    @Test
    void nothing_advances_while_the_match_goes_on() {
        OneCardGameEngine adapter = engine(NOW, 3);
        GameEngine.Result result = adapter.apply(aboutToGoDownToOne(), 0, PlayCard.of(heart(9)));

        assertThat(adapter.advance(result.newState(), new ArrayList<>(result.events())))
                .isEqualTo(GameEngine.Advance.NONE);
        assertThat(published).isEmpty();
    }

    @Test
    void desertion_maps_to_the_three_port_outcomes() {
        OneCardState threeSeats = aboutToGoDownToOne();
        OneCardState twoSeats = seats(hand(heart(9), club(3)), hand(spade(4), spade(6))).top(heart(5)).turn(0).build();
        OneCardState alreadyOut = seats(hand(heart(9), club(3)), hand(), hand(diamond(4), diamond(6)))
                .eliminated(1, Elimination.Reason.BANKRUPT, 20).top(heart(5)).turn(0).build();
        List<GameEvent> outbound = new ArrayList<>();

        when(store.load("room-1")).thenReturn(Optional.empty());
        assertThat(engine(NOW, 3).desert(2, 102L, outbound)).isEqualTo(GameEngine.DesertOutcome.NOT_APPLICABLE);

        when(store.load("room-1")).thenReturn(Optional.of(alreadyOut));
        assertThat(engine(NOW, 3).desert(1, 101L, outbound)).isEqualTo(GameEngine.DesertOutcome.NOT_APPLICABLE);
        verify(store, never()).save(anyString(), any());
        assertThat(outbound).isEmpty();

        when(store.load("room-1")).thenReturn(Optional.of(threeSeats));
        assertThat(engine(NOW, 3).desert(2, 102L, outbound)).isEqualTo(GameEngine.DesertOutcome.MATCH_CONTINUES);
        verify(store).save(eq("room-1"), argThat(saved -> !saved.alive(2) && !saved.ended()));
        assertThat(outbound).isNotEmpty();
        assertThat(published).isEmpty();

        outbound.clear();
        when(store.load("room-1")).thenReturn(Optional.of(twoSeats));
        assertThat(engine(NOW, 2).desert(1, 101L, outbound)).isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);
        assertThat(outbound).last().isInstanceOf(OneCardEvent.MatchEnded.class);
        assertThat(published).singleElement().isInstanceOfSatisfying(OneCardMatchCompleted.class,
                done -> assertThat(done.result().reason()).isEqualTo(EndReason.LAST_STANDING));
    }

    @Test
    void views_and_queries_read_the_state_with_the_clock() {
        OneCardState raced = raced(engine(NOW, 3));
        OneCardGameEngine later = engine(NOW + 1_000, 3);

        assertThat(later.phaseName(raced)).isEqualTo("RACE");
        assertThat(later.pendingSeats(raced)).isEmpty();
        assertThat(later.isRoundOver(raced)).isFalse();
        assertThat(((TableView) later.publicView(raced)).race().remainingMillis()).isEqualTo(2_000);
        assertThat(later.privateView(raced, 0)).contains(new PrivateView(0, List.of(club(3)), raced.version()));
        assertThat(later.legalActions(raced, 0)).hasSize(1);

        when(store.load("room-1")).thenReturn(Optional.empty());
        assertThat(later.isMatchOver()).isFalse();
    }

    @Test
    void foreign_state_or_action_types_are_rejected() {
        OneCardGameEngine adapter = engine(NOW, 3);

        assertThatThrownBy(() -> adapter.phaseName(mock(GameState.class)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.apply(aboutToGoDownToOne(), 0, mock(GameAction.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
