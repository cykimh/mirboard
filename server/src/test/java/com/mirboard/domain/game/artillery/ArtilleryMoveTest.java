package com.mirboard.domain.game.artillery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.action.ArtilleryActionRejectedException;
import com.mirboard.domain.game.artillery.action.RejectionReason;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.invariant.ArtilleryInvariantChecker;
import com.mirboard.domain.game.artillery.physics.Walker;
import com.mirboard.domain.game.artillery.rules.Item;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.rules.UnitKind;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.artillery.state.ShotReport;
import com.mirboard.domain.game.artillery.state.Unit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * S6a 걷기 확정 (D-228, `docs/rules-artillery.md` §4.1·§5.1) — {@code MOVE} 를 나눠 걸은 뒤 쏘기 ≡ 한 번에 걸으며 쏘기, 거절은 상태 무변경,
 * 걷다 떨어지기(비치명 → 차례 계속 / 치명 → 탈락·연출 대기 / 생존 ≤1 → 종료), 넘기기 딜레이에 이동 딜레이가 붙는다.
 */
class ArtilleryMoveTest {

    private static final ArtilleryEngine ENGINE = new ArtilleryEngine();
    private static final long NOW = 7_000_000L;

    // ---------- 같음 검사 ----------

    /**
     * 떨어짐이 없는 한 {@code MOVE(a) → MOVE(b) → FIRE(moveDx=c)} 와 {@code FIRE(moveDx=a+b+c)} 의 도착점·남은 연료·딜레이·탄도 부분이 같다.
     * 대본 {@code move} 는 FIRE 안에서 걸은 부분만 싣는다(그래서 비교하지 않는다).
     */
    @Test
    void split_moves_then_fire_equals_one_fire_with_the_summed_move() {
        Random random = new Random(20261111L);
        int compared = 0;
        for (int sample = 0; sample < 200; sample++) {
            int seats = 2 + random.nextInt(5);
            List<UnitKind> kinds = new ArrayList<>();
            for (int i = 0; i < seats; i++) {
                kinds.add(UnitKind.values()[random.nextInt(UnitKind.values().length)]);
            }
            ArtilleryState s0 = ENGINE.startMatch(random.nextLong(), seats, List.of(), kinds,
                    ArtilleryEngine.Setup.SOLO).newState();
            int seat = s0.turnSeat();
            int dir = random.nextBoolean() ? 1 : -1;
            int a = dir * (1 + random.nextInt(40));
            int b = dir * (1 + random.nextInt(40));
            int c = dir * random.nextInt(40);
            int angle = random.nextInt(181);
            int power = 20 + random.nextInt(81);
            Unit me = s0.unit(seat);
            Shell shell = random.nextInt(3) == 0 ? me.kind().special() : Shell.PEA;
            Item item = random.nextInt(3) == 0 ? me.items().get(random.nextInt(me.items().size())) : null;

            // 나눠 걷기
            ArtilleryState split = s0;
            boolean fell = false;
            for (int dx : new int[] {a, b}) {
                if (fell) {
                    break;
                }
                try {
                    ArtilleryEngine.Result r = ENGINE.apply(split, seat, new ArtilleryAction.Move(dx), NOW);
                    ArtilleryEvent.UnitMoved moved = (ArtilleryEvent.UnitMoved) r.events().getFirst();
                    fell |= moved.fall() != null;
                    split = r.newState();
                } catch (ArtilleryActionRejectedException e) {
                    assertThat(e.reason()).isEqualTo(RejectionReason.CANNOT_MOVE);   // 벽·연료 — 상태 무변경
                }
            }
            if (fell) {
                continue;
            }
            ArtilleryEngine.Result splitFire = ENGINE.apply(split, seat, new ArtilleryAction.Fire(c, angle, power, shell, item), NOW);
            ArtilleryEngine.Result oneFire = ENGINE.apply(s0, seat, new ArtilleryAction.Fire(a + b + c, angle, power, shell, item), NOW);
            ShotReport x = ((ArtilleryEvent.ShotResolved) splitFire.events().getFirst()).report();
            ShotReport y = ((ArtilleryEvent.ShotResolved) oneFire.events().getFirst()).report();
            if (x.move().fall() != null || y.move().fall() != null) {
                continue;
            }
            compared++;
            assertThat(x.move().toX()).as("sample %d toX", sample).isEqualTo(y.move().toX());
            assertThat(x.move().toY()).isEqualTo(y.move().toY());
            assertThat(x.move().fuelLeft()).isEqualTo(y.move().fuelLeft());
            assertThat(x.stages()).isEqualTo(y.stages());
            assertThat(x.eliminated()).isEqualTo(y.eliminated());
            assertThat(x.units()).isEqualTo(y.units());
            assertThat(x.heal()).isEqualTo(y.heal());
            assertThat(splitFire.newState().units()).isEqualTo(oneFire.newState().units());   // 딜레이 포함
            assertThat(splitFire.newState().craters()).isEqualTo(oneFire.newState().craters());
        }
        assertThat(compared).as("samples compared without a fall").isGreaterThan(120);
    }

    @Test
    void moves_spend_fuel_across_the_turn_and_fire_walks_with_what_is_left() {
        ArtilleryState s = ENGINE.startMatch(41L, 2, List.of(), List.of(UnitKind.SPROUT, UnitKind.SPROUT),
                ArtilleryEngine.Setup.SOLO).newState();
        int seat = s.turnSeat();
        int dir = s.unit(seat).x() < 320 ? 1 : -1;
        ArtilleryEngine.Result r = ENGINE.apply(s, seat, new ArtilleryAction.Move(dir * 10), NOW);
        ArtilleryEvent.UnitMoved m = (ArtilleryEvent.UnitMoved) r.events().getFirst();
        assertThat(r.events()).hasSize(1);
        assertThat(m.seat()).isEqualTo(seat);
        assertThat(m.turn()).isEqualTo(s.turnNo());
        assertThat(m.path()).hasSize(2 * 10);
        assertThat(m.fuelLeft()).isLessThanOrEqualTo(100 - 10);
        ArtilleryState after = r.newState();
        assertThat(after.phase()).isEqualTo(Phase.AIMING);
        assertThat(after.turnSeat()).isEqualTo(seat);
        assertThat(after.unit(seat).x()).isEqualTo(m.toX());
        assertThat(after.unit(seat).footY()).isEqualTo(m.toY());
        assertThat(after.turnFuelLeft()).isEqualTo(m.fuelLeft());
        assertThat(after.moveFuelUsed()).isEqualTo(100 - m.fuelLeft());
        assertThat(ArtilleryInvariantChecker.check(after)).isEmpty();

        // 연료를 다 쓰면 다음 MOVE 는 CANNOT_MOVE, FIRE.moveDx 는 0 걸음
        ArtilleryState tired = after;
        for (int i = 0; i < 20 && tired.turnFuelLeft() > 0; i++) {
            try {
                tired = ENGINE.apply(tired, seat, new ArtilleryAction.Move(dir * 130), NOW).newState();
            } catch (ArtilleryActionRejectedException e) {
                dir = -dir;   // 벽 — 반대로
            }
        }
        assertThat(tired.turnFuelLeft()).isZero();
        ArtilleryState spent = tired;
        assertRejected(() -> ENGINE.apply(spent, seat, new ArtilleryAction.Move(1), NOW), RejectionReason.CANNOT_MOVE);
        ShotReport shot = ((ArtilleryEvent.ShotResolved) ENGINE.apply(spent, seat,
                new ArtilleryAction.Fire(50, 90, 30, Shell.PEA), NOW).events().getFirst()).report();
        assertThat(shot.move().path()).isEmpty();
        assertThat(shot.move().fuelLeft()).isZero();
    }

    // ---------- 거절 ----------

    @Test
    void rejected_moves_leave_the_state_untouched() {
        ArtilleryState s = ENGINE.startMatch(5L, 3, List.of(), List.of(UnitKind.SPROUT, UnitKind.ACORN, UnitKind.DANDELION),
                ArtilleryEngine.Setup.SOLO).newState();
        int seat = s.turnSeat();
        int other = (seat + 1) % 3;
        assertRejected(() -> ENGINE.apply(s, other, new ArtilleryAction.Move(5), NOW), RejectionReason.NOT_YOUR_TURN);
        assertRejected(() -> ENGINE.apply(s, seat, new ArtilleryAction.Move(0), NOW), RejectionReason.OUT_OF_RANGE);
        assertRejected(() -> ENGINE.apply(s, seat, new ArtilleryAction.Move(131), NOW), RejectionReason.OUT_OF_RANGE);
        assertRejected(() -> ENGINE.apply(s, seat, new ArtilleryAction.Move(-131), NOW), RejectionReason.OUT_OF_RANGE);

        // 전장 끝 — 첫 걸음이 경계 밖
        Unit atEdge = s.unit(seat).withPosition(Walker.MIN_X, s.unit(seat).footY());
        ArtilleryState edge = ArtilleryFixtures.withUnit(s, atEdge);
        assertRejected(() -> ENGINE.apply(edge, seat, new ArtilleryAction.Move(-3), NOW), RejectionReason.CANNOT_MOVE);

        // 연출 대기·고르기·끝
        ArtilleryState resolving = ENGINE.apply(s, seat, new ArtilleryAction.Fire(0, 90, 20, Shell.PEA), NOW).newState();
        assertThat(resolving.phase()).isEqualTo(Phase.RESOLVING);
        assertRejected(() -> ENGINE.apply(resolving, seat, new ArtilleryAction.Move(5), NOW), RejectionReason.NOT_YOUR_TURN);
        ArtilleryState picking = ENGINE.startMatch(5L, 3, List.of(), NOW, ArtilleryEngine.Setup.SOLO).newState();
        assertRejected(() -> ENGINE.apply(picking, 0, new ArtilleryAction.Move(5), NOW), RejectionReason.NOT_YOUR_TURN);
        ArtilleryState ended = ENGINE.desert(ENGINE.desert(s, other).newState(), (other + 1) % 3).newState();
        assertThat(ended.ended()).isTrue();
        assertRejected(() -> ENGINE.apply(ended, seat, new ArtilleryAction.Move(5), NOW), RejectionReason.MATCH_OVER);
    }

    @Test
    void preview_input_is_not_an_action_and_is_never_a_legal_action() {
        ArtilleryState s = ENGINE.startMatch(5L, 2, List.of()).newState();
        assertRejected(() -> ENGINE.apply(s, s.turnSeat(), new ArtilleryAction.Preview(45, Shell.PEA, 1), NOW),
                RejectionReason.INVALID_ACTION);
        assertThat(ENGINE.legalActions(s, s.turnSeat()))
                .noneMatch(a -> a instanceof ArtilleryAction.Preview || a instanceof ArtilleryAction.Move);
    }

    // ---------- 걷다 떨어지기 ----------

    @Test
    void a_non_lethal_fall_keeps_the_turn_and_applies_damage() {
        Pit pit = pit(2, 0, false);
        ArtilleryEngine.Result r = ENGINE.apply(pit.state(), pit.seat(), new ArtilleryAction.Move(pit.dir() * 130), NOW);
        ArtilleryEvent.UnitMoved m = (ArtilleryEvent.UnitMoved) r.events().getFirst();
        assertThat(m.fall()).isNotNull();
        assertThat(m.fall().damage()).isPositive();
        assertThat(m.eliminated()).isNull();
        assertThat(m.hpAfter()).isEqualTo(100 - m.fall().damage());
        ArtilleryState after = r.newState();
        assertThat(after.phase()).isEqualTo(Phase.AIMING);
        assertThat(after.turnSeat()).isEqualTo(pit.seat());
        assertThat(after.unit(pit.seat()).hp()).isEqualTo(m.hpAfter());
        assertThat(after.unit(pit.seat()).footY()).isEqualTo(m.fall().toY());
        assertThat(ArtilleryInvariantChecker.check(after)).isEmpty();
        // 그 자리에서 이어서 쏠 수 있다
        assertThat(ENGINE.apply(after, pit.seat(), new ArtilleryAction.Fire(0, 90, 30, Shell.PEA), NOW).newState().phase())
                .isIn(Phase.RESOLVING, Phase.ENDED);
    }

    @Test
    void a_lethal_fall_eliminates_then_waits_then_opens_the_next_turn() {
        Pit pit = pit(3, 1, false);
        ArtilleryEngine.Result r = ENGINE.apply(pit.state(), pit.seat(), new ArtilleryAction.Move(pit.dir() * 130), NOW);
        ArtilleryEvent.UnitMoved m = (ArtilleryEvent.UnitMoved) r.events().getFirst();
        assertThat(m.eliminated()).isEqualTo(Unit.Cause.KNOCKED_OUT);
        assertThat(m.hpAfter()).isZero();
        ArtilleryState after = r.newState();
        assertThat(after.phase()).isEqualTo(Phase.RESOLVING);
        assertThat(after.turnSeat()).isEqualTo(-1);
        assertThat(after.lastShot()).isNull();
        assertThat(after.resolveUntil()).isGreaterThanOrEqualTo(NOW + ArtilleryEngine.MIN_RESOLVE_MS);
        assertThat(after.unit(pit.seat()).alive()).isFalse();
        assertThat(after.unit(pit.seat()).out().order()).isEqualTo(1);
        assertThat(after.turnStartedAt()).isZero();
        assertThat(after.moveFuelUsed()).isZero();
        assertThat(ArtilleryInvariantChecker.check(after)).isEmpty();
        ArtilleryState next = ENGINE.expire(after, after.resolveUntil()).newState();
        assertThat(next.phase()).isEqualTo(Phase.AIMING);
        assertThat(next.turnSeat()).isNotEqualTo(pit.seat());
        assertThat(next.turnStartedAt()).isEqualTo(after.resolveUntil());
        assertThat(ArtilleryInvariantChecker.check(next)).isEmpty();
    }

    @Test
    void walking_into_the_cloud_sea_with_one_rival_left_ends_the_match() {
        Pit pit = pit(2, 0, true);
        assertThat(pit.state().seatCount()).isEqualTo(2);
        ArtilleryEngine.Result r = ENGINE.apply(pit.state(), pit.seat(), new ArtilleryAction.Move(pit.dir() * 130), NOW);
        ArtilleryEvent.UnitMoved m = (ArtilleryEvent.UnitMoved) r.events().getFirst();
        assertThat(m.eliminated()).isEqualTo(Unit.Cause.CLOUD_SEA);
        assertThat(m.fall().intoCloud()).isTrue();
        assertThat(r.newState().ended()).isTrue();
        assertThat(r.events().getLast()).isInstanceOf(ArtilleryEvent.MatchEnded.class);
        assertThat(r.newState().result().winners()).containsExactly(1 - pit.seat());
    }

    // ---------- 넘기기 딜레이 (§4.2) ----------

    @Test
    void skipping_after_walking_adds_the_move_delay() {
        ArtilleryState s = ENGINE.startMatch(77L, 3, List.of(), List.of(UnitKind.SPROUT, UnitKind.SPROUT, UnitKind.SPROUT),
                ArtilleryEngine.Setup.SOLO).newState();
        int seat = s.turnSeat();
        int before = s.unit(seat).delay();
        ArtilleryState idle = ENGINE.apply(s, seat, new ArtilleryAction.Skip(), NOW).newState();
        assertThat(idle.unit(seat).delay()).isEqualTo(before + ArtilleryEngine.SKIP_DELAY);

        int dir = s.unit(seat).x() < 320 ? 1 : -1;
        ArtilleryState walked = ENGINE.apply(s, seat, new ArtilleryAction.Move(dir * 21), NOW).newState();
        int used = walked.moveFuelUsed();
        assertThat(used).isGreaterThanOrEqualTo(21);
        ArtilleryState skipped = ENGINE.apply(walked, seat, new ArtilleryAction.Skip(), NOW).newState();
        assertThat(skipped.unit(seat).delay())
                .isEqualTo(before + ArtilleryEngine.SKIP_DELAY + used / ArtilleryEngine.FUEL_PER_DELAY);
        ArtilleryState timedOut = ENGINE.timeout(walked, NOW).newState();
        assertThat(timedOut.unit(seat).delay()).isEqualTo(skipped.unit(seat).delay());
        assertThat(skipped.moveFuelUsed()).isZero();
    }

    // ---------- 도우미 ----------

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, RejectionReason reason) {
        assertThatThrownBy(call).isInstanceOf(ArtilleryActionRejectedException.class)
                .satisfies(e -> assertThat(((ArtilleryActionRejectedException) e).reason()).isEqualTo(reason));
    }

    private record Pit(ArtilleryState state, int seat, int dir) {
    }

    /**
     * 걸어서 떨어지는 자리를 시드로 찾는다(결정적 — 시드 1부터 차례로) — {@code seats} 인 판 차례 주인이 한쪽으로 {@code dir × 130} 걸으면 떨어지고,
     * {@code intoCloud} 가 바라는 대로인 첫 판(구름 바다가 아니면 피해 > 0, {@code hp == 0} 이면 피해 < 체력 — 비치명). 차례 주인 체력은
     * {@code hp} 로 바꾼다(0 이면 그대로).
     */
    private static Pit pit(int seats, int hp, boolean intoCloud) {
        for (long seed = 1; seed < 3000; seed++) {
            ArtilleryState s = ENGINE.startMatch(seed, seats, List.of(),
                    java.util.Collections.nCopies(seats, UnitKind.SPROUT), ArtilleryEngine.Setup.SOLO).newState();
            int seat = s.turnSeat();
            for (int dir : new int[] {1, -1}) {
                Unit me = s.unit(seat);
                Walker.Walk w = Walker.walk(ArtilleryEngine.terrainOf(s), me.x(), me.footY(), dir * 130, 100,
                        s.cloudLevel());
                boolean wanted = w.fall() != null && w.fall().intoCloud() == intoCloud
                        && (intoCloud || w.fall().damage() > 0 && (hp > 0 || w.fall().damage() < me.hp()));
                if (!wanted) {
                    continue;
                }
                ArtilleryState found = hp > 0 ? ArtilleryFixtures.withUnit(s, me.withHp(hp)) : s;
                return new Pit(found.withTurnClock(NOW, 0), seat, dir);
            }
        }
        throw new AssertionError("no walking fall found");
    }
}
