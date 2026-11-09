package com.mirboard.domain.game.artillery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.event.ArtilleryMatchCompleted;
import com.mirboard.domain.game.artillery.persistence.ArtilleryStateStore;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.state.ArtilleryPublicView;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** S2 포트 어댑터 — 연출 대기 타이머·뷰(시드 없음)·봇 안전망·탈주·기록 발행. 저장소는 모의라 Docker 없이 돈다. */
class ArtilleryGameEngineTest {

    private static final List<Long> PLAYERS = List.of(10L, 11L, 12L);
    private static final long T0 = 5_000_000L;

    private final ArtilleryStateStore store = mock(ArtilleryStateStore.class);
    private final List<Object> published = new ArrayList<>();

    private ArtilleryGameEngine adapter(long nowMillis) {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC);
        return new ArtilleryGameEngine(new GameContext("r1", PLAYERS), new ArtilleryEngine(), store, clock, published::add);
    }

    private static ArtilleryState fired() {
        ArtilleryState s = ArtilleryFixtures.start(3L, 3);
        return ArtilleryFixtures.ENGINE.apply(s, s.turnSeat(), new ArtilleryAction.Fire(0, 70, 50, Shell.PEA), T0).newState();
    }

    @Test
    void resolving_has_a_timer_and_no_pending_seat_then_on_timer_opens_the_next_turn() {
        ArtilleryState waiting = fired();
        long until = waiting.resolveUntil();
        assertThat(adapter(T0).pendingSeats(waiting)).isEmpty();
        assertThat(adapter(T0).timer(waiting)).contains(Duration.ofMillis(until - T0));
        assertThat(adapter(until + 50).timer(waiting)).contains(Duration.ZERO);
        Optional<GameEngine.Result> next = adapter(until).onTimer(waiting);
        assertThat(next).isPresent();
        ArtilleryState aiming = (ArtilleryState) next.get().newState();
        assertThat(adapter(until).pendingSeats(aiming)).containsExactly(aiming.turnSeat());
        assertThat(adapter(until).timer(aiming)).isEmpty();
        assertThat(adapter(until).onTimer(aiming)).isEmpty();
    }

    @Test
    void the_public_view_hides_the_seed_and_there_is_no_private_view() throws Exception {
        ArtilleryState s0 = ArtilleryFixtures.start(0x5EED_1234_ABCDL, 3);
        ArtilleryState waiting = ArtilleryFixtures.ENGINE.apply(s0, s0.turnSeat(),
                new ArtilleryAction.Fire(0, 70, 50, Shell.PEA), T0).newState();
        ArtilleryPublicView view = (ArtilleryPublicView) adapter(T0).publicView(waiting);
        assertThat(view.lastShot()).isNotNull();
        assertThat(view.resolveRemainingMs()).isPositive();
        assertThat(view.terrain()).hasSize(640);
        String json = new ObjectMapper().writeValueAsString(view);
        assertThat(json).doesNotContain("\"seed\"").doesNotContain(Long.toString(waiting.seed()));
        assertThat(adapter(T0).privateView(waiting, 0)).isEmpty();
    }

    @Test
    void a_failing_or_invalid_bot_policy_falls_back_to_skip() {
        ArtilleryState s = ArtilleryFixtures.start(3L, 3);
        int seat = s.turnSeat();
        ArtilleryGameEngine a = adapter(T0);
        assertThat(a.chooseBotAction(s, seat, (st, se) -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(ArtilleryAction.Skip.class);
        assertThat(a.chooseBotAction(s, seat, (st, se) -> new ArtilleryAction.Fire(0, 300, 50, Shell.PEA)))
                .isInstanceOf(ArtilleryAction.Skip.class);
        assertThat(a.chooseBotAction(s, (seat + 1) % 3, (st, se) -> new ArtilleryAction.Skip())).isNull();
        assertThat(a.botAction(s, seat, new Random(1))).isInstanceOf(ArtilleryAction.Fire.class);
        assertThat(a.timeoutAction(s, seat)).isInstanceOf(ArtilleryAction.Skip.class);
    }

    @Test
    void advance_records_once_when_the_match_ends() {
        ArtilleryState s = ArtilleryFixtures.start(21L, 2);
        int me = s.turnSeat();
        s = ArtilleryFixtures.withUnit(s, s.unit(1 - me).withHp(1));
        ArtilleryAction.Fire hit = ArtilleryFixtures.directHit(s, 1 - me, Shell.PEA);
        ArtilleryGameEngine a = new ArtilleryGameEngine(new GameContext("r1", List.of(10L, 11L)), new ArtilleryEngine(),
                store, Clock.systemUTC(), published::add);
        GameEngine.Result r = a.apply(s, me, hit);
        assertThat(a.advance(r.newState(), new ArrayList<GameEvent>(r.events()))).isEqualTo(new GameEngine.Advance(true, true));
        assertThat(published).singleElement().isInstanceOf(ArtilleryMatchCompleted.class);
        assertThat(a.advance(r.newState(), List.of())).isEqualTo(GameEngine.Advance.NONE);
    }

    @Test
    void desertion_maps_to_the_port_outcomes_and_saves() {
        ArtilleryState s = ArtilleryFixtures.start(51L, 3);
        when(store.load("r1")).thenReturn(Optional.of(s));
        List<GameEvent> out = new ArrayList<>();
        assertThat(adapter(T0).desert(s.turnSeat(), 10L, out)).isEqualTo(GameEngine.DesertOutcome.MATCH_CONTINUES);
        assertThat(out).first().isInstanceOf(ArtilleryEvent.UnitEliminated.class);
        when(store.load("r1")).thenReturn(Optional.empty());
        assertThat(adapter(T0).desert(0, 10L, new ArrayList<>())).isEqualTo(GameEngine.DesertOutcome.NOT_APPLICABLE);
    }
}
