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
import com.mirboard.domain.game.artillery.rules.Item;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.rules.UnitKind;
import com.mirboard.domain.game.artillery.state.ArtilleryPublicView;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Crater;
import com.mirboard.domain.game.artillery.state.MatchResult;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.artillery.state.ShotReport;
import com.mirboard.domain.game.artillery.state.Unit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

/**
 * 씨앗포 순수 룰 엔진 (`docs/rules-artillery.md`). 저장소·시계·난수 객체가 없다 — 난수는 상태의 시드에서 SplitMix64 로, 시각은 인자로
 * 받는다. 같은 (상태, 좌석, 액션, 시각)이면 같은 결과다(골든 테스트가 고정).
 *
 * <p>한 발은 {@link #apply} 한 번에 전부 해소된다 — 이동 → 탄도 → 폭발 → 낙하 → 탈락. 결과는 즉시 상태에 반영되고 단계만
 * {@code RESOLVING}(연출 대기)이 된다. 대기가 끝나면 {@link #expire} 가 다음 차례를 연다(포트 엔진 타이머가 부른다).
 *
 * <p>판은 고르기 단계 {@code PICKING}(D-224)으로 시작한다 — 전원이 동시에 유닛을 고르고, 다 고르거나 15초가 지나면
 * ({@link #expire}) 공개하고 첫 차례를 연다.
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
    public static final long MIN_RESOLVE_MS = 600;
    public static final long MAX_RESOLVE_MS = 18_000;
    /** 고르기 단계 길이 (D-224). */
    public static final long PICK_MS = 15_000;

    private final long minResolveMs;
    private final long maxResolveMs;

    /** 룰 그대로의 연출 대기(600ms~18s, §9.2). */
    public ArtilleryEngine() {
        this(MAX_RESOLVE_MS);
    }

    /**
     * 연출 대기 상한만 바꾼다 — 봇끼리 도는 통합 테스트가 판을 빨리 끝내려고 쓴다({@code mirboard.artillery.resolve-cap-millis}).
     * 하한은 600ms 와 이 상한 중 작은 값. 운영은 기본 생성자와 같다.
     */
    public ArtilleryEngine(long maxResolveMs) {
        this.maxResolveMs = Math.max(0, maxResolveMs);
        this.minResolveMs = Math.min(MIN_RESOLVE_MS, this.maxResolveMs);
    }

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

    /** 맵 생성 → 출발 자리 배정 → 고르기 단계 (§9.1, D-224). 고르기가 끝나는 시각은 {@code nowMillis + PICK_MS}. */
    public Result startMatch(long seed, int seatCount, List<Integer> botSeats, long nowMillis) {
        ArtilleryState s = placed(seed, seatCount, botSeats, Phase.PICKING, nowMillis + PICK_MS);
        return new Result(s, List.of());
    }

    /** 고르기 없이 바로 첫 차례 — 종류를 미리 정한 판(테스트·시뮬레이션). */
    public Result startMatch(long seed, int seatCount, List<Integer> botSeats, List<UnitKind> kinds) {
        if (kinds.size() != seatCount) {
            throw new IllegalArgumentException("kinds size " + kinds.size() + " != seats " + seatCount);
        }
        ArtilleryState s = placed(seed, seatCount, botSeats, Phase.PICKING, 0);
        List<Unit> units = new ArrayList<>(s.units());
        for (int i = 0; i < seatCount; i++) {
            units.set(i, units.get(i).picked(kinds.get(i)));
        }
        List<ArtilleryEvent> events = new ArrayList<>();
        ArtilleryState started = reveal(withUnits(s, units), events);
        return new Result(started, events);
    }

    /** 전원 새싹포로 바로 시작(S1~S3 의 규칙 그대로 — 기존 테스트). */
    public Result startMatch(long seed, int seatCount, List<Integer> botSeats) {
        List<UnitKind> kinds = new ArrayList<>();
        for (int i = 0; i < seatCount; i++) {
            kinds.add(UnitKind.SPROUT);
        }
        return startMatch(seed, seatCount, botSeats, kinds);
    }

    private ArtilleryState placed(long seed, int seatCount, List<Integer> botSeats, Phase phase, long pickUntil) {
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
            units.add(new Unit(seat, spawn.x(), spawn.footY(), MAX_HP, Shell.PUMPKIN.perMatch(), null,
                    null, 0, List.of()));
        }
        return new ArtilleryState(seed, seatCount, botSeats, units, List.of(), phase,
                -1, -1, 0, 0, Fixed.HEIGHT, 0, 0, null, null, pickUntil);
    }

    /** 고르기 끝 (D-224): 안 고른 좌석은 새싹포, 전원 무장 → 공개 이벤트 → 첫 차례(시드로 고른 좌석). */
    private ArtilleryState reveal(ArtilleryState s, List<ArtilleryEvent> events) {
        List<Unit> units = new ArrayList<>(s.units());
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            units.set(i, (u.kind() == null ? u.picked(UnitKind.SPROUT) : u).equipped());
        }
        int first = SplitMix64.below(s.seed(), 6000, s.seatCount());
        ArtilleryState armed = new ArtilleryState(s.seed(), s.seatCount(), s.botSeats(), units, s.craters(),
                Phase.AIMING, -1, first, 0, 0, s.cloudLevel(), 0, s.eliminations(), null, null, 0L);
        events.add(new ArtilleryEvent.UnitsRevealed(
                units.stream().map(u -> ArtilleryPublicView.UnitView.of(u, false)).toList()));
        return beginTurn(armed, first, 0, events);
    }

    // ---------- 액션 ----------

    public Result apply(ArtilleryState state, int seat, ArtilleryAction action, long nowMillis) {
        if (state.ended()) {
            throw new ArtilleryActionRejectedException(RejectionReason.MATCH_OVER);
        }
        if (state.phase() == Phase.PICKING) {
            if (!(action instanceof ArtilleryAction.Pick pick)) {
                throw new ArtilleryActionRejectedException(RejectionReason.NOT_YOUR_TURN);
            }
            return pick(state, seat, pick);
        }
        if (state.phase() != Phase.AIMING || state.turnSeat() != seat) {
            throw new ArtilleryActionRejectedException(RejectionReason.NOT_YOUR_TURN);
        }
        return switch (action) {
            case ArtilleryAction.Fire fire -> fire(state, seat, fire, nowMillis);
            case ArtilleryAction.Skip __ -> skip(state, seat, ArtilleryEvent.TurnSkipped.Reason.SKIP);
            case ArtilleryAction.Pick __ -> throw new ArtilleryActionRejectedException(RejectionReason.INVALID_ACTION);
            case null -> throw new ArtilleryActionRejectedException(RejectionReason.INVALID_ACTION);
        };
    }

    private Result pick(ArtilleryState state, int seat, ArtilleryAction.Pick pick) {
        if (!pendingSeats(state).contains(seat)) {
            throw new ArtilleryActionRejectedException(RejectionReason.NOT_YOUR_TURN);
        }
        if (pick.unit() == null) {
            throw new ArtilleryActionRejectedException(RejectionReason.INVALID_ACTION);
        }
        List<Unit> units = new ArrayList<>(state.units());
        units.set(seat, units.get(seat).picked(pick.unit()));
        List<ArtilleryEvent> events = new ArrayList<>();
        events.add(new ArtilleryEvent.UnitPicked(seat));
        ArtilleryState s = withUnits(state, units);
        if (pendingSeats(s).isEmpty()) {
            s = reveal(s, events);
        }
        return new Result(s, events);
    }

    /** 시간 초과 = 넘김(§4-5). 차례가 아니면 null. */
    public Result timeout(ArtilleryState state, long nowMillis) {
        if (state.phase() != Phase.AIMING) {
            return null;
        }
        return skip(state, state.turnSeat(), ArtilleryEvent.TurnSkipped.Reason.TIMEOUT);
    }

    /** 연출 대기(§9.2)·고르기(D-224)의 남은 시간. 둘 다 아니면 없음. */
    public OptionalLong remainingMillis(ArtilleryState state, long nowMillis) {
        return switch (state.phase()) {
            case RESOLVING -> OptionalLong.of(state.resolveUntil() - nowMillis);
            case PICKING -> OptionalLong.of(state.pickUntil() - nowMillis);
            default -> OptionalLong.empty();
        };
    }

    /** 연출 대기 끝 → 다음 차례, 고르기 끝 → 공개·첫 차례. 둘 다 아니면 null. */
    public Result expire(ArtilleryState state, long nowMillis) {
        List<ArtilleryEvent> events = new ArrayList<>();
        return switch (state.phase()) {
            case RESOLVING -> new Result(nextTurn(state, events), events);
            case PICKING -> new Result(reveal(state, events), events);
            default -> null;
        };
    }

    /** AIMING = [차례 좌석], PICKING = 아직 안 고른 살아 있는 좌석 전부(동시), 그 밖 = []. */
    public List<Integer> pendingSeats(ArtilleryState state) {
        return switch (state.phase()) {
            case AIMING -> List.of(state.turnSeat());
            case PICKING -> state.units().stream().filter(u -> u.alive() && u.kind() == null).map(Unit::seat).toList();
            default -> List.of();
        };
    }

    /**
     * 합법 액션의 <b>대표 표본</b> — 각도 15° × 힘 10 단위 × (콩알·쓸 수 있는 탄 / 콩알 + 남은 아이템 하나씩) + 넘김. 연속 공간이라
     * 전부를 셀 수 없다. 포트 기본 봇·무작위 시뮬레이션용이고, 실제 검증은 {@link #apply} 의 범위 검사다. 고르기 단계면 세 종류.
     */
    public List<ArtilleryAction> legalActions(ArtilleryState state, int seat) {
        if (!pendingSeats(state).contains(seat)) {
            return List.of();
        }
        if (state.phase() == Phase.PICKING) {
            return Arrays.stream(UnitKind.values()).<ArtilleryAction>map(ArtilleryAction.Pick::new).toList();
        }
        List<ArtilleryAction> actions = new ArrayList<>();
        Unit me = state.unit(seat);
        List<Shell> shells = Arrays.stream(Shell.values()).filter(sh -> me.ammo(sh) != 0).toList();
        for (int angle = 0; angle <= 180; angle += 15) {
            for (int power = 10; power <= 100; power += 10) {
                for (Shell shell : shells) {
                    actions.add(new ArtilleryAction.Fire(0, angle, power, shell));
                }
                for (Item item : me.items()) {
                    actions.add(new ArtilleryAction.Fire(0, angle, power, Shell.PEA, item));
                }
            }
        }
        actions.add(new ArtilleryAction.Skip());
        return actions;
    }

    /** 범위·탄·아이템 검사만 — 봇 정책 결과 확인용. */
    public static boolean validFire(ArtilleryState state, int seat, ArtilleryAction.Fire fire) {
        Unit me = state.unit(seat);
        return Math.abs(fire.moveDx()) <= ArtilleryAction.MAX_MOVE
                && fire.angle() >= 0 && fire.angle() <= 180
                && fire.power() >= 0 && fire.power() <= 100
                && me.ammo(fire.shell()) != 0
                && (fire.item() == null || me.has(fire.item()));
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
        if (s.phase() == Phase.PICKING && pendingSeats(s).isEmpty()) {
            s = reveal(s, events);
            return new Desertion(s.ended() ? DesertOutcome.MATCH_ENDED : DesertOutcome.MATCH_CONTINUES, s, events);
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
            applyOp(t, c);
        }
        return t;
    }

    /** 지형 연산 하나 — 구멍(지우기) 또는 둔덕(채우기, 몸 자리는 비움). */
    public static void applyOp(Terrain t, Crater c) {
        if (c.fill()) {
            t.fill(c.x(), c.y(), c.r(), c.spare(), Crater.SPARE_RADIUS);
        } else {
            t.carve(c.x(), c.y(), c.r());
        }
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
            if (state.unit(seat).ammo(fire.shell()) == 0) {
                throw new ArtilleryActionRejectedException(RejectionReason.NO_AMMO);
            }
            if (fire.item() != null && !state.unit(seat).has(fire.item())) {
                throw new ArtilleryActionRejectedException(RejectionReason.NO_ITEM);
            }
            throw new ArtilleryActionRejectedException(RejectionReason.OUT_OF_RANGE);
        }
        Terrain t = terrainOf(state);
        int cloud = state.cloudLevel();
        int order = state.eliminations() + 1;
        List<Unit> units = new ArrayList<>(state.units());
        int[] hpBefore = units.stream().mapToInt(Unit::hp).toArray();
        List<Crater> craters = new ArrayList<>(state.craters());
        List<ShotReport.Eliminated> eliminated = new ArrayList<>();

        // 아이템 (§6.4) — 쓰는 순간 없어진다. 물뿌리개는 걷기 전에.
        Unit shooter = units.get(seat);
        ShotReport.Heal heal = null;
        if (fire.item() != null) {
            shooter = shooter.without(fire.item());
            if (fire.item() == Item.WATERING_CAN) {
                int after = Math.min(shooter.maxHp(), shooter.hp() + Item.HEAL);
                heal = new ShotReport.Heal(after - shooter.hp(), after);
                shooter = shooter.withHp(after);
            }
        }

        // 이동
        UnitKind kind = shooter.kindOrDefault();
        Walker.Walk walk = Walker.walk(t, shooter.x(), shooter.footY(), fire.moveDx(), kind.fuel(), cloud);
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
        knockOut(state, units, order, hpBefore, eliminated);

        // 발사 — 겹씨앗이면 첫 발이 다 해소된 뒤 한 발 더(§6.4)
        long windAccel = Ballistics.windAccel(state.wind(), fire.item() == Item.PINWHEEL ? 0 : kind.windPercent());
        int volleys = fire.item() == Item.TWIN_SEED ? 2 : 1;
        List<ShotReport.Stage> stages = new ArrayList<>();
        long stageTicks = 0;
        for (int v = 0; v < volleys; v++) {
            Unit me = units.get(seat);
            if (!me.alive() || units.stream().filter(Unit::alive).count() < 2 && v > 0) {
                break;
            }
            if (v == 0) {
                units.set(seat, me.spend(fire.shell()));
            }
            Volley volley = volley(t, units, seat, fire.angle(), fire.power(), fire.shell(), windAccel, cloud, order,
                    hpBefore, craters);
            stages.add(volley.stage());
            stageTicks += volley.ticks();
            knockOut(state, units, order, hpBefore, eliminated);
        }
        int eliminations = eliminated.isEmpty() ? state.eliminations() : order;

        long ticks = (long) walk.steps() * WALK_TICKS_PER_STEP + walkFallTicks + stageTicks;
        long durationMs = Math.clamp(ticks * 1000 / 60, minResolveMs, maxResolveMs);
        ShotReport report = new ShotReport(seat, fire.angle(), fire.power(), fire.shell(), fire.item(), state.wind(),
                heal, move, stages, eliminated, units.stream().map(ShotReport.UnitView::of).toList(), durationMs);

        List<ArtilleryEvent> events = new ArrayList<>();
        events.add(new ArtilleryEvent.ShotResolved(report));
        ArtilleryState resolved = copy(state, units, craters, Phase.RESOLVING, -1, seat, state.turnNo(), state.wind(),
                cloud, nowMillis + durationMs, eliminations, report);
        ArtilleryState ended = maybeEnd(resolved, events);
        return new Result(ended != null ? ended : resolved, events);
    }

    /** 체력 0 인 살아 있는 포대를 이번 순간({@code order})에 탈락시킨다 — 한 발 해소 전체가 한 순간이다. */
    private static void knockOut(ArtilleryState state, List<Unit> units, int order, int[] hpBefore,
                                 List<ShotReport.Eliminated> eliminated) {
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            if (u.alive() && u.hp() == 0) {
                u = u.eliminated(new Unit.Elimination(order, Unit.Cause.KNOCKED_OUT, hpBefore[i]));
                units.set(i, u);
            }
            int seat = i;
            if (state.unit(i).alive() && !u.alive() && eliminated.stream().noneMatch(e -> e.seat() == seat)) {
                eliminated.add(new ShotReport.Eliminated(u.seat(), u.out().cause()));
            }
        }
    }

    private record Volley(ShotReport.Stage stage, long ticks) {
    }

    /**
     * 한 발 (§6.1~§6.3): 씨앗들이 같은 지형 위를 날고(홀씨탄은 꼭대기에서 셋으로), 다 내려앉으면 구멍·둔덕을 순서대로 → 피해(포대마다 씨앗
     * 피해의 합, 좌석 순) → 낙하(좌석 순). {@code t}·{@code units}·{@code craters} 를 고친다.
     */
    private static Volley volley(Terrain t, List<Unit> units, int seat, int angle, int power, Shell shell,
                                 long windAccel, int cloud, int order, int[] hpBefore, List<Crater> craters) {
        Unit me = units.get(seat);
        List<Ballistics.Target> targets = units.stream().filter(Unit::alive)
                .map(u -> new Ballistics.Target(u.seat(), u.x(), u.footY())).toList();
        Ballistics.Trace first = Ballistics.fire(t, targets, seat, me.x(), me.footY(), angle, power, windAccel, cloud,
                shell.splits());
        List<Ballistics.Trace> traces = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        traces.add(first);
        starts.add(0);
        if (first.end() == Ballistics.End.SPLIT) {
            Ballistics.Launch apex = first.apex();
            for (int k = -1; k <= 1; k++) {
                Ballistics.Launch child = new Ballistics.Launch(apex.x(), apex.y(),
                        apex.vx() + (long) k * Shell.SPORE_SPREAD, apex.vy(), apex.tick());
                traces.add(Ballistics.fly(t, targets, seat, child, windAccel, cloud));
                starts.add(apex.tick());
            }
        }
        int flightTicks = 0;
        boolean exploded = false;
        List<ShotReport.Shot> shots = new ArrayList<>();
        List<Ballistics.Trace> blasts = new ArrayList<>();
        for (int i = 0; i < traces.size(); i++) {
            Ballistics.Trace tr = traces.get(i);
            int start = starts.get(i);
            flightTicks = Math.max(flightTicks, tr.ticks());
            Crater crater = null;
            if (tr.exploded()) {
                exploded = true;
                blasts.add(tr);
                if (shell.fills()) {
                    List<Integer> spare = new ArrayList<>();
                    for (Unit u : units) {
                        if (u.alive()) {
                            spare.add(u.x());
                            spare.add(Body.centerY(u.footY()));
                        }
                    }
                    crater = new Crater(tr.impactX(), tr.impactY(), shell.craterRadius(), true, spare);
                } else {
                    crater = new Crater(tr.impactX(), tr.impactY(), shell.craterRadius());
                }
            }
            shots.add(new ShotReport.Shot(tr.path(), tr.end().name(), tr.impactX(), tr.impactY(), crater,
                    tr.hitSeat(), start, tr.ticks() - start));
        }
        for (ShotReport.Shot shot : shots) {
            if (shot.crater() != null) {
                applyOp(t, shot.crater());
                craters.add(shot.crater());
            }
        }
        List<ShotReport.Damage> damages = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            if (!u.alive()) {
                continue;
            }
            int dmg = 0;
            boolean direct = false;
            for (Ballistics.Trace tr : blasts) {
                long dx = u.x() - tr.impactX();
                long dy = Body.centerY(u.footY()) - tr.impactY();
                int d = Fixed.isqrt(dx * dx + dy * dy);
                boolean hit = tr.hitSeat() == u.seat();
                direct |= hit;
                dmg += shell.damageAt(d, hit);
            }
            if (dmg > 0) {
                int after = Math.max(0, u.hp() - dmg);
                units.set(i, u.withHp(after));
                damages.add(new ShotReport.Damage(u.seat(), u.hp() - after, after, direct));
            }
        }
        // 낙하 (§6.3) — 좌석 순
        List<ShotReport.Fall> falls = new ArrayList<>();
        int maxFall = 0;
        if (exploded) {
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
        long ticks = flightTicks + maxFall + (exploded ? EXPLOSION_TICKS : 0);
        return new Volley(new ShotReport.Stage(shots, damages, falls), ticks);
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
        } else if (state.botSeats().size() < state.seatCount()
                && alive.stream().allMatch(u -> state.botSeats().contains(u.seat()))) {
            // 사람이 있던 판에서 사람이 모두 탈락했다. 처음부터 봇만인 판(시험·관전)은 끝까지 간다.
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

    private static ArtilleryState withUnits(ArtilleryState s, List<Unit> units) {
        return new ArtilleryState(s.seed(), s.seatCount(), s.botSeats(), units, s.craters(), s.phase(), s.turnSeat(),
                s.lastSeat(), s.turnNo(), s.wind(), s.cloudLevel(), s.resolveUntil(), s.eliminations(), s.lastShot(),
                s.result(), s.pickUntil());
    }

    private static ArtilleryState copy(ArtilleryState s, List<Unit> units, List<Crater> craters, Phase phase,
                                       int turnSeat, int lastSeat, int turnNo, int wind, int cloud, long resolveUntil,
                                       int eliminations, ShotReport lastShot) {
        return new ArtilleryState(s.seed(), s.seatCount(), s.botSeats(), units, craters, phase, turnSeat, lastSeat,
                turnNo, wind, cloud, resolveUntil, eliminations, lastShot, s.result(), s.pickUntil());
    }
}
