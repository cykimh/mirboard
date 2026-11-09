package com.mirboard.domain.game.artillery;

import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.action.ArtilleryActionRejectedException;
import com.mirboard.domain.game.artillery.action.RejectionReason;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.physics.Ballistics;
import com.mirboard.domain.game.artillery.physics.Body;
import com.mirboard.domain.game.artillery.physics.Fixed;
import com.mirboard.domain.game.artillery.physics.MapGenerator;
import com.mirboard.domain.game.artillery.physics.SplitMix64;
import com.mirboard.domain.game.artillery.physics.Terrain;
import com.mirboard.domain.game.artillery.physics.TerrainCache;
import com.mirboard.domain.game.artillery.physics.Walker;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Crater;
import com.mirboard.domain.game.artillery.state.MatchResult;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.artillery.state.ShotReport;
import com.mirboard.domain.game.artillery.state.Unit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

/**
 * 씨앗포 순수 룰 엔진 (`docs/rules-artillery.md`). 저장소·시계·난수 객체가 없다 — 난수는 상태의 시드에서 SplitMix64 로, 시각은 인자로
 * 받는다. 같은 (상태, 좌석, 액션, 시각)이면 같은 결과다(골든 테스트가 고정).
 *
 * <p>한 발은 {@link #apply} 한 번에 전부 해소된다 — 이동 → 탄도 → 폭발 → 낙하 → 탈락. 결과는 즉시 상태에 반영되고 단계만
 * {@code RESOLVING}(연출 대기)이 된다. 대기가 끝나면 {@link #expire} 가 다음 차례를 연다(포트 엔진 타이머가 부른다).
 */
public final class ArtilleryEngine {

    public static final int MAX_HP = 100;
    public static final int FUEL = 100;
    public static final int WIND_MAX = 10;
    public static final int SUDDEN_DEATH_TURNS_PER_SEAT = 10;
    public static final int CLOUD_RISE = 15;
    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 6;
    static final int EXPLOSION_TICKS = 36;
    static final int WALK_TICKS_PER_STEP = 2;
    static final long MIN_RESOLVE_MS = 600;
    static final long MAX_RESOLVE_MS = 18_000;

    public record Result(ArtilleryState newState, List<ArtilleryEvent> events) {
        public Result {
            events = List.copyOf(events);
        }
    }

    public enum DesertOutcome {
        NOT_APPLICABLE, MATCH_CONTINUES, MATCH_ENDED
    }

    public record Desertion(DesertOutcome outcome, ArtilleryState newState, List<ArtilleryEvent> events) {
        public Desertion {
            events = List.copyOf(events);
        }
    }

    // ---------- 시작 ----------

    /** 맵 생성 → 출발 자리 배정 → 첫 차례 (§9.1). */
    public Result startMatch(long seed, int seatCount, List<Integer> botSeats) {
        if (seatCount < MIN_PLAYERS || seatCount > MAX_PLAYERS) {
            throw new IllegalArgumentException("seat count out of range: " + seatCount);
        }
        MapGenerator.GeneratedMap map = TerrainCache.base(seed, seatCount);
        int[] order = new int[seatCount];
        for (int i = 0; i < seatCount; i++) {
            order[i] = i;
        }
        for (int i = seatCount - 1; i > 0; i--) {
            int j = SplitMix64.below(seed, 5000 + i, i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        List<Unit> units = new ArrayList<>(seatCount);
        for (int seat = 0; seat < seatCount; seat++) {
            MapGenerator.Spawn spawn = map.spawns().get(order[seat]);
            units.add(new Unit(seat, spawn.x(), spawn.footY(), MAX_HP, Shell.PUMPKIN.perMatch(), null));
        }
        int first = SplitMix64.below(seed, 6000, seatCount);
        ArtilleryState state = new ArtilleryState(seed, seatCount, botSeats, units, List.of(), Phase.AIMING,
                -1, first, 0, 0, Fixed.HEIGHT, 0, 0, null, null);
        List<ArtilleryEvent> events = new ArrayList<>();
        state = beginTurn(state, first, 0, events);
        return new Result(state, events);
    }

    // ---------- 액션 ----------

    public Result apply(ArtilleryState state, int seat, ArtilleryAction action, long nowMillis) {
        if (state.ended()) {
            throw new ArtilleryActionRejectedException(RejectionReason.MATCH_OVER);
        }
        if (state.phase() != Phase.AIMING || state.turnSeat() != seat) {
            throw new ArtilleryActionRejectedException(RejectionReason.NOT_YOUR_TURN);
        }
        return switch (action) {
            case ArtilleryAction.Fire fire -> fire(state, seat, fire, nowMillis);
            case ArtilleryAction.Skip __ -> skip(state, seat, ArtilleryEvent.TurnSkipped.Reason.SKIP);
            case null -> throw new ArtilleryActionRejectedException(RejectionReason.INVALID_ACTION);
        };
    }

    /** 시간 초과 = 넘김(§4-5). 차례가 아니면 null. */
    public Result timeout(ArtilleryState state, long nowMillis) {
        if (state.phase() != Phase.AIMING) {
            return null;
        }
        return skip(state, state.turnSeat(), ArtilleryEvent.TurnSkipped.Reason.TIMEOUT);
    }

    /** 연출 대기의 남은 시간(§9.2). RESOLVING 이 아니면 없음. */
    public OptionalLong remainingMillis(ArtilleryState state, long nowMillis) {
        if (state.phase() != Phase.RESOLVING) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(state.resolveUntil() - nowMillis);
    }

    /** 연출 대기 끝 → 다음 차례. RESOLVING 이 아니면 null. */
    public Result expire(ArtilleryState state, long nowMillis) {
        if (state.phase() != Phase.RESOLVING) {
            return null;
        }
        List<ArtilleryEvent> events = new ArrayList<>();
        ArtilleryState next = nextTurn(state, events);
        return new Result(next, events);
    }

    public List<Integer> pendingSeats(ArtilleryState state) {
        return state.phase() == Phase.AIMING ? List.of(state.turnSeat()) : List.of();
    }

    /**
     * 합법 액션의 <b>대표 표본</b> — 각도 15° × 힘 10 단위 × 쓸 수 있는 탄 + 넘김. 연속 공간이라 전부를 셀 수 없다. 포트 기본 봇·무작위
     * 시뮬레이션용이고, 실제 검증은 {@link #apply} 의 범위 검사다.
     */
    public List<ArtilleryAction> legalActions(ArtilleryState state, int seat) {
        if (!pendingSeats(state).contains(seat)) {
            return List.of();
        }
        List<ArtilleryAction> actions = new ArrayList<>();
        boolean pumpkin = state.unit(seat).pumpkins() > 0;
        for (int angle = 0; angle <= 180; angle += 15) {
            for (int power = 10; power <= 100; power += 10) {
                actions.add(new ArtilleryAction.Fire(0, angle, power, Shell.PEA));
                if (pumpkin) {
                    actions.add(new ArtilleryAction.Fire(0, angle, power, Shell.PUMPKIN));
                }
            }
        }
        actions.add(new ArtilleryAction.Skip());
        return actions;
    }

    /** 범위 검사만 — 봇 정책 결과 확인용. */
    public static boolean validFire(ArtilleryState state, int seat, ArtilleryAction.Fire fire) {
        return Math.abs(fire.moveDx()) <= ArtilleryAction.MAX_MOVE
                && fire.angle() >= 0 && fire.angle() <= 180
                && fire.power() >= 0 && fire.power() <= 100
                && (fire.shell() != Shell.PUMPKIN || state.unit(seat).pumpkins() > 0);
    }

    // ---------- 탈주 (§11) ----------

    public Desertion desert(ArtilleryState state, int seat) {
        if (state.ended() || seat < 0 || seat >= state.seatCount() || !state.unit(seat).alive()) {
            return new Desertion(DesertOutcome.NOT_APPLICABLE, state, List.of());
        }
        List<ArtilleryEvent> events = new ArrayList<>();
        int order = state.eliminations() + 1;
        List<Unit> units = new ArrayList<>(state.units());
        Unit u = units.get(seat);
        units.set(seat, u.eliminated(new Unit.Elimination(order, Unit.Cause.DESERTED, u.hp())));
        events.add(new ArtilleryEvent.UnitEliminated(seat, Unit.Cause.DESERTED, state.cloudLevel()));
        ArtilleryState s = copy(state, units, state.craters(), state.phase(), state.turnSeat(), state.lastSeat(),
                state.turnNo(), state.wind(), state.cloudLevel(), state.resolveUntil(), order, state.lastShot());
        ArtilleryState ended = maybeEnd(s, events);
        if (ended != null) {
            return new Desertion(DesertOutcome.MATCH_ENDED, ended, events);
        }
        if (s.phase() == Phase.AIMING && s.turnSeat() == seat) {
            s = nextTurn(s, events);
            if (s.ended()) {
                return new Desertion(DesertOutcome.MATCH_ENDED, s, events);
            }
        }
        return new Desertion(DesertOutcome.MATCH_CONTINUES, s, events);
    }

    // ---------- 지형·바람 ----------

    /** 시드로 만든 원본에 구멍을 차례로 지운 지형(새 사본). */
    public static Terrain terrainOf(ArtilleryState state) {
        Terrain t = TerrainCache.base(state.seed(), state.seatCount()).terrain().copy();
        for (Crater c : state.craters()) {
            t.carve(c.x(), c.y(), c.r());
        }
        return t;
    }

    /** 차례 번호의 바람 (§7). */
    public static int windAt(long seed, int turnNo) {
        return SplitMix64.below(seed, 1000L + turnNo, 2 * WIND_MAX + 1) - WIND_MAX;
    }

    /** 차례 상한 (§8) — 이 차례 번호에 닿기 전에 판이 끝난다. */
    public static int turnLimit(int seatCount) {
        return SUDDEN_DEATH_TURNS_PER_SEAT * seatCount + Fixed.HEIGHT / CLOUD_RISE + 1;
    }

    // ---------- 발사 (§5·§6) ----------

    private Result fire(ArtilleryState state, int seat, ArtilleryAction.Fire fire, long nowMillis) {
        if (!validFire(state, seat, fire)) {
            if (fire.shell() == Shell.PUMPKIN && state.unit(seat).pumpkins() <= 0) {
                throw new ArtilleryActionRejectedException(RejectionReason.NO_AMMO);
            }
            throw new ArtilleryActionRejectedException(RejectionReason.OUT_OF_RANGE);
        }
        Terrain t = terrainOf(state);
        int cloud = state.cloudLevel();
        int order = state.eliminations() + 1;
        List<Unit> units = new ArrayList<>(state.units());
        int[] hpBefore = units.stream().mapToInt(Unit::hp).toArray();
        List<Crater> craters = new ArrayList<>(state.craters());

        // 이동
        Unit shooter = units.get(seat);
        Walker.Walk walk = Walker.walk(t, shooter.x(), shooter.footY(), fire.moveDx(), FUEL, cloud);
        shooter = shooter.withPosition(walk.x(), walk.footY());
        ShotReport.Fall walkFall = null;
        int walkFallTicks = 0;
        if (walk.fall() != null) {
            Body.Fall f = walk.fall();
            walkFallTicks = f.distance();
            if (f.intoCloud()) {
                shooter = shooter.eliminated(new Unit.Elimination(order, Unit.Cause.CLOUD_SEA, hpBefore[seat]));
            } else {
                shooter = shooter.withHp(Math.max(0, shooter.hp() - f.damage()));
            }
            walkFall = new ShotReport.Fall(seat, walk.x(), f.fromY(), f.toY(), f.damage(), shooter.hp(), f.intoCloud());
        }
        units.set(seat, shooter);
        ShotReport.Move move = new ShotReport.Move(walk.path(), walk.x(), walk.footY(), walk.fuelLeft(), walkFall);

        List<ShotReport.Damage> damages = new ArrayList<>();
        List<ShotReport.Fall> falls = new ArrayList<>();
        ShotReport.Shot shot = null;
        int flightTicks = 0;
        int maxFall = 0;
        boolean exploded = false;
        if (shooter.alive() && shooter.hp() > 0) {
            if (fire.shell() == Shell.PUMPKIN) {
                shooter = shooter.withPumpkins(shooter.pumpkins() - 1);
                units.set(seat, shooter);
            }
            List<Ballistics.Target> targets = units.stream().filter(Unit::alive)
                    .map(u -> new Ballistics.Target(u.seat(), u.x(), u.footY())).toList();
            Ballistics.Trace trace = Ballistics.fire(t, targets, seat, shooter.x(), shooter.footY(),
                    fire.angle(), fire.power(), state.wind(), cloud);
            flightTicks = trace.ticks();
            Crater crater = null;
            if (trace.exploded()) {
                exploded = true;
                Shell shell = fire.shell();
                crater = new Crater(trace.impactX(), trace.impactY(), shell.craterRadius());
                t.carve(crater.x(), crater.y(), crater.r());
                craters.add(crater);
                for (int i = 0; i < units.size(); i++) {
                    Unit u = units.get(i);
                    if (!u.alive()) {
                        continue;
                    }
                    long dx = u.x() - trace.impactX();
                    long dy = Body.centerY(u.footY()) - trace.impactY();
                    int d = Fixed.isqrt(dx * dx + dy * dy);
                    boolean direct = trace.hitSeat() == u.seat();
                    int dmg = shell.damageAt(d, direct);
                    if (dmg > 0) {
                        int after = Math.max(0, u.hp() - dmg);
                        units.set(i, u.withHp(after));
                        damages.add(new ShotReport.Damage(u.seat(), u.hp() - after, after, direct));
                    }
                }
                // 낙하 (§6.3) — 좌석 순
                for (int i = 0; i < units.size(); i++) {
                    Unit u = units.get(i);
                    if (!u.alive()) {
                        continue;
                    }
                    Body.Fall f = Body.fall(t, u.x(), u.footY(), cloud);
                    if (f.distance() == 0 && !f.intoCloud()) {
                        continue;
                    }
                    maxFall = Math.max(maxFall, f.distance());
                    Unit moved = u.withPosition(u.x(), f.toY());
                    if (f.intoCloud()) {
                        moved = moved.eliminated(new Unit.Elimination(order, Unit.Cause.CLOUD_SEA, hpBefore[i]));
                    } else {
                        moved = moved.withHp(Math.max(0, moved.hp() - f.damage()));
                    }
                    units.set(i, moved);
                    falls.add(new ShotReport.Fall(u.seat(), u.x(), f.fromY(), f.toY(), f.intoCloud() ? 0 : f.damage(),
                            moved.hp(), f.intoCloud()));
                }
            }
            shot = new ShotReport.Shot(trace.path(), trace.end().name(), trace.impactX(), trace.impactY(), crater,
                    trace.hitSeat(), trace.ticks());
        }

        // 체력 0 → 탈락 (같은 순간)
        List<ShotReport.Eliminated> eliminated = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            Unit before = state.unit(i);
            if (u.alive() && u.hp() == 0) {
                u = u.eliminated(new Unit.Elimination(order, Unit.Cause.KNOCKED_OUT, hpBefore[i]));
                units.set(i, u);
            }
            if (before.alive() && !u.alive()) {
                eliminated.add(new ShotReport.Eliminated(u.seat(), u.out().cause()));
            }
        }
        int eliminations = eliminated.isEmpty() ? state.eliminations() : order;

        long ticks = (long) walk.steps() * WALK_TICKS_PER_STEP + walkFallTicks + flightTicks + maxFall
                + (exploded ? EXPLOSION_TICKS : 0);
        long durationMs = Math.clamp(ticks * 1000 / 60, MIN_RESOLVE_MS, MAX_RESOLVE_MS);
        ShotReport report = new ShotReport(seat, fire.angle(), fire.power(), fire.shell(), state.wind(), move, shot,
                damages, falls, eliminated, units.stream().map(ShotReport.UnitView::of).toList(), durationMs);

        List<ArtilleryEvent> events = new ArrayList<>();
        events.add(new ArtilleryEvent.ShotResolved(report));
        ArtilleryState resolved = copy(state, units, craters, Phase.RESOLVING, -1, seat, state.turnNo(), state.wind(),
                cloud, nowMillis + durationMs, eliminations, report);
        ArtilleryState ended = maybeEnd(resolved, events);
        return new Result(ended != null ? ended : resolved, events);
    }

    private Result skip(ArtilleryState state, int seat, ArtilleryEvent.TurnSkipped.Reason reason) {
        List<ArtilleryEvent> events = new ArrayList<>();
        events.add(new ArtilleryEvent.TurnSkipped(seat, reason));
        ArtilleryState s = copy(state, state.units(), state.craters(), state.phase(), state.turnSeat(), seat,
                state.turnNo(), state.wind(), state.cloudLevel(), 0, state.eliminations(), state.lastShot());
        return new Result(nextTurn(s, events), events);
    }

    // ---------- 차례 ----------

    /** lastSeat 다음 살아 있는 좌석으로 차례 번호 +1. */
    private ArtilleryState nextTurn(ArtilleryState state, List<ArtilleryEvent> events) {
        int next = nextAlive(state.units(), state.lastSeat(), false);
        if (next < 0) {
            ArtilleryState ended = maybeEnd(state, events);
            if (ended != null) {
                return ended;
            }
            throw new IllegalStateException("no alive seat but match not over");
        }
        return beginTurn(state, next, state.turnNo() + 1, events);
    }

    /** 차례 시작 (§4-1): 밀물 → (끝났나) → 바람 → AIMING. {@code candidate} 가 밀물에 빠지면 그 다음 살아 있는 좌석. */
    private ArtilleryState beginTurn(ArtilleryState state, int candidate, int turnNo, List<ArtilleryEvent> events) {
        List<Unit> units = new ArrayList<>(state.units());
        int cloud = state.cloudLevel();
        int eliminations = state.eliminations();
        if (turnNo >= SUDDEN_DEATH_TURNS_PER_SEAT * state.seatCount()) {
            cloud -= CLOUD_RISE;
            int order = eliminations + 1;
            boolean any = false;
            for (int i = 0; i < units.size(); i++) {
                Unit u = units.get(i);
                if (u.alive() && u.footY() >= cloud) {
                    units.set(i, u.eliminated(new Unit.Elimination(order, Unit.Cause.CLOUD_SEA, u.hp())));
                    events.add(new ArtilleryEvent.UnitEliminated(i, Unit.Cause.CLOUD_SEA, cloud));
                    any = true;
                }
            }
            if (any) {
                eliminations = order;
            }
        }
        int wind = windAt(state.seed(), turnNo);
        ArtilleryState s = copy(state, units, state.craters(), Phase.AIMING, -1, state.lastSeat(), turnNo, wind,
                cloud, 0, eliminations, null);
        ArtilleryState ended = maybeEnd(s, events);
        if (ended != null) {
            return ended;
        }
        int seat = units.get(candidate).alive() ? candidate : nextAlive(units, candidate, false);
        s = copy(s, units, s.craters(), Phase.AIMING, seat, seat, turnNo, wind, cloud, 0, eliminations, null);
        events.add(new ArtilleryEvent.TurnChanged(seat, turnNo, wind, cloud));
        return s;
    }

    private static int nextAlive(List<Unit> units, int from, boolean inclusive) {
        int n = units.size();
        for (int i = inclusive ? 0 : 1; i <= n; i++) {
            int s = Math.floorMod(from + i, n);
            if (units.get(s).alive()) {
                return s;
            }
        }
        return -1;
    }

    // ---------- 종료·순위 (§9.3) ----------

    /** 끝났으면 ENDED 상태(이벤트 추가), 아니면 null. */
    private ArtilleryState maybeEnd(ArtilleryState state, List<ArtilleryEvent> events) {
        List<Unit> alive = state.aliveUnits();
        MatchResult.EndReason reason;
        if (alive.size() == 1) {
            reason = MatchResult.EndReason.LAST_STANDING;
        } else if (alive.isEmpty()) {
            reason = MatchResult.EndReason.ALL_DOWN;
        } else if (alive.stream().allMatch(u -> state.botSeats().contains(u.seat()))) {
            reason = MatchResult.EndReason.NO_HUMANS;
        } else {
            return null;
        }
        MatchResult result = new MatchResult(reason, placements(state.units()));
        events.add(new ArtilleryEvent.MatchEnded(reason, result.placements()));
        return new ArtilleryState(state.seed(), state.seatCount(), state.botSeats(), state.units(), state.craters(),
                Phase.ENDED, -1, state.lastSeat(), state.turnNo(), state.wind(), state.cloudLevel(), 0,
                state.eliminations(), state.lastShot(), result);
    }

    static List<MatchResult.Placement> placements(List<Unit> units) {
        record Key(int group, int a, int b) {
        }
        Comparator<Key> better = Comparator.comparingInt(Key::group).thenComparingInt(Key::a).thenComparingInt(Key::b);
        List<Key> keys = new ArrayList<>();
        for (Unit u : units) {
            if (u.alive()) {
                keys.add(new Key(0, -u.hp(), 0));
            } else if (u.out().cause() != Unit.Cause.DESERTED) {
                keys.add(new Key(1, -u.out().order(), -u.out().hpBefore()));
            } else {
                keys.add(new Key(2, 0, 0));
            }
        }
        List<MatchResult.Placement> placements = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            Key mine = keys.get(i);
            int rank = 1 + (int) keys.stream().filter(k -> better.compare(k, mine) < 0).count();
            Unit u = units.get(i);
            placements.add(new MatchResult.Placement(u.seat(), rank, u.hp(),
                    !u.alive() && u.out().cause() == Unit.Cause.DESERTED));
        }
        placements.sort(Comparator.comparingInt(MatchResult.Placement::rank).thenComparingInt(MatchResult.Placement::seat));
        return placements;
    }

    private static ArtilleryState copy(ArtilleryState s, List<Unit> units, List<Crater> craters, Phase phase,
                                       int turnSeat, int lastSeat, int turnNo, int wind, int cloud, long resolveUntil,
                                       int eliminations, ShotReport lastShot) {
        return new ArtilleryState(s.seed(), s.seatCount(), s.botSeats(), units, craters, phase, turnSeat, lastSeat,
                turnNo, wind, cloud, resolveUntil, eliminations, lastShot, s.result());
    }
}
