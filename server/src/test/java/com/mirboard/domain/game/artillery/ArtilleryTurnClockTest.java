package com.mirboard.domain.game.artillery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.invariant.ArtilleryInvariantChecker;
import com.mirboard.domain.game.artillery.persistence.ArtilleryStateStore;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.rules.UnitKind;
import com.mirboard.domain.game.artillery.state.ArtilleryPublicView;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * S6a 차례 전체 마감 (D-228, `docs/plans/artillery-s6.md` §4.3 — 요트 D-183 과 같은 방식). {@code MOVE} 를 거듭해도 엔진 타이머는 차례 시작 +
 * T 로 줄고, 만기면 시간 초과 넘김(+ 이동 딜레이). 공개 뷰가 엔진 계산 남은 시간을 싣는다. D-228 이전 JSON(필드 없음)은 타이머 없음 → 첫
 * {@code MOVE} 가 채운다.
 */
class ArtilleryTurnClockTest {

    private static final ArtilleryEngine ENGINE = new ArtilleryEngine();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TURN_SECONDS = 30;
    private static final long T0 = 9_000_000L;

    private final ArtilleryStateStore store = mock(ArtilleryStateStore.class);

    private ArtilleryGameEngine adapter(long nowMillis, int turnSeconds) {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC);
        GameContext ctx = new GameContext("r1", List.of(1L, 2L, 3L), 0, 0, List.of(), Map.of(), turnSeconds);
        return new ArtilleryGameEngine(ctx, ENGINE, store, clock, e -> { });
    }

    /** 고르기 → 공개(T0) — 첫 차례 시계가 T0 에서 시작한 판. */
    private static ArtilleryState startedAt(long t) {
        ArtilleryState picking = ENGINE.startMatch(21L, 3, List.of(), t - ArtilleryEngine.PICK_MS,
                ArtilleryEngine.Setup.SOLO).newState();
        return ENGINE.expire(picking, t).newState();
    }

    @Test
    void every_new_turn_starts_its_clock_and_resets_the_move_fuel() {
        ArtilleryState s = startedAt(T0);
        assertThat(s.phase()).isEqualTo(Phase.AIMING);
        assertThat(s.turnStartedAt()).isEqualTo(T0);
        assertThat(s.moveFuelUsed()).isZero();
        int seat = s.turnSeat();
        int dir = s.unit(seat).x() < 320 ? 1 : -1;
        ArtilleryState walked = ENGINE.apply(s, seat, new ArtilleryAction.Move(dir * 8), T0 + 4_000).newState();
        assertThat(walked.turnStartedAt()).isEqualTo(T0);   // MOVE 는 시계를 다시 걸지 않는다
        ArtilleryState fired = ENGINE.apply(walked, seat, new ArtilleryAction.Fire(0, 90, 20, Shell.PEA), T0 + 5_000).newState();
        assertThat(fired.turnStartedAt()).isZero();
        assertThat(fired.moveFuelUsed()).isZero();
        ArtilleryState next = ENGINE.expire(fired, fired.resolveUntil()).newState();
        assertThat(next.turnStartedAt()).isEqualTo(fired.resolveUntil());
        assertThat(next.moveFuelUsed()).isZero();
        ArtilleryState afterSkip = ENGINE.apply(next, next.turnSeat(), new ArtilleryAction.Skip(), T0 + 20_000).newState();
        assertThat(afterSkip.turnStartedAt()).isEqualTo(T0 + 20_000);
        assertThat(ArtilleryInvariantChecker.check(afterSkip)).isEmpty();
    }

    @Test
    void moves_do_not_refill_the_turn_timer_and_expiry_is_a_timeout_skip_with_the_move_delay() {
        ArtilleryState s = startedAt(T0);
        int seat = s.turnSeat();
        int dir = s.unit(seat).x() < 320 ? 1 : -1;
        long full = TURN_SECONDS * 1000L;
        assertThat(adapter(T0, TURN_SECONDS).timer(s)).contains(Duration.ofMillis(full));
        ArtilleryState cur = s;
        for (int i = 1; i <= 4; i++) {
            long now = T0 + i * 3_000L;
            cur = (ArtilleryState) adapter(now, TURN_SECONDS).apply(cur, seat, new ArtilleryAction.Move(dir * 4)).newState();
            assertThat(adapter(now, TURN_SECONDS).timer(cur)).contains(Duration.ofMillis(full - i * 3_000L));
            ArtilleryPublicView view = (ArtilleryPublicView) adapter(now, TURN_SECONDS).publicView(cur);
            assertThat(view.turnRemainingMs()).isEqualTo(full - i * 3_000L);
            assertThat(view.fuel()).isEqualTo(cur.turnFuelLeft());
        }
        assertThat(adapter(T0 + full + 10, TURN_SECONDS).timer(cur)).contains(Duration.ZERO);
        int used = cur.moveFuelUsed();
        int delayBefore = cur.unit(seat).delay();
        Optional<GameEngine.Result> due = adapter(T0 + full, TURN_SECONDS).onTimer(cur);
        assertThat(due).isPresent();
        assertThat(due.get().events().getFirst())
                .isEqualTo(new ArtilleryEvent.TurnSkipped(seat, ArtilleryEvent.TurnSkipped.Reason.TIMEOUT));
        ArtilleryState after = (ArtilleryState) due.get().newState();
        assertThat(after.unit(seat).delay())
                .isEqualTo(delayBefore + ArtilleryEngine.SKIP_DELAY + used / ArtilleryEngine.FUEL_PER_DELAY);
        assertThat(after.turnSeat()).isNotEqualTo(seat);
        assertThat(after.turnStartedAt()).isEqualTo(T0 + full);
    }

    @Test
    void no_turn_limit_or_no_clock_means_no_aiming_timer() {
        ArtilleryState s = startedAt(T0);
        assertThat(adapter(T0, 0).timer(s)).isEmpty();
        assertThat(adapter(T0, 0).onTimer(s)).isEmpty();
        assertThat(((ArtilleryPublicView) adapter(T0, 0).publicView(s)).turnRemainingMs()).isNull();
        ArtilleryState clockless = s.withTurnClock(0L, 0);
        assertThat(adapter(T0, TURN_SECONDS).timer(clockless)).isEmpty();
        assertThat(adapter(T0, TURN_SECONDS).onTimer(clockless)).isEmpty();
        // 연출 대기·고르기 타이머는 그대로
        ArtilleryState resolving = ENGINE.apply(s, s.turnSeat(), new ArtilleryAction.Fire(0, 90, 20, Shell.PEA), T0).newState();
        assertThat(adapter(T0, TURN_SECONDS).timer(resolving)).contains(Duration.ofMillis(resolving.resolveUntil() - T0));
        assertThat(((ArtilleryPublicView) adapter(T0, TURN_SECONDS).publicView(resolving)).turnRemainingMs()).isNull();
    }

    /** 배포 전에 시작해 Redis 에 남은 판 — 필드가 없는 JSON 을 읽으면 0, 타이머 없음, 첫 MOVE 가 지금으로 채운다. */
    @Test
    void old_json_without_the_turn_clock_reads_as_zero_and_the_first_move_fills_it() throws Exception {
        ArtilleryState s = ENGINE.startMatch(33L, 3, List.of(), List.of(UnitKind.SPROUT, UnitKind.SPROUT, UnitKind.SPROUT),
                ArtilleryEngine.Setup.SOLO).newState();
        ObjectNode tree = (ObjectNode) JSON.readTree(JSON.writeValueAsString(s.withTurnClock(T0, 0)));
        tree.remove("turnStartedAt");
        tree.remove("moveFuelUsed");
        ArtilleryState old = JSON.treeToValue(tree, ArtilleryState.class);
        assertThat(old.turnStartedAt()).isZero();
        assertThat(old.moveFuelUsed()).isZero();
        assertThat(adapter(T0, TURN_SECONDS).timer(old)).isEmpty();
        int seat = old.turnSeat();
        int dir = old.unit(seat).x() < 320 ? 1 : -1;
        ArtilleryState moved = (ArtilleryState) adapter(T0 + 7_000, TURN_SECONDS)
                .apply(old, seat, new ArtilleryAction.Move(dir * 5)).newState();
        assertThat(moved.turnStartedAt()).isEqualTo(T0 + 7_000);
        assertThat(adapter(T0 + 7_000, TURN_SECONDS).timer(moved)).contains(Duration.ofSeconds(TURN_SECONDS));
        // 옛 판의 FIRE 는 그대로(연료 가득에서 걷기)
        ArtilleryState fresh = ENGINE.apply(old, seat, new ArtilleryAction.Fire(dir * 5, 90, 20, Shell.PEA), T0).newState();
        assertThat(fresh.unit(seat).x()).isEqualTo(moved.unit(seat).x());
    }
}
