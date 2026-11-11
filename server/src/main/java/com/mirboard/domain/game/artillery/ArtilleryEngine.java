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
import com.mirboard.domain.game.artillery.state.TurnOrder;
import com.mirboard.domain.game.artillery.state.Unit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
 *
 * <p>D-225·D-226: 판은 {@link Setup} 으로 시작한다 — 2:2 팀전(팀 = 좌석 홀짝, 한 팀만 남으면 끝)과 차례 규칙(포트리스식 딜레이). 인자에
 * {@code Setup} 이 없는 옛 {@code startMatch} 들은 개인전·좌석 순서(D-226 이전 규칙)다 — 기존 테스트·골든이 그 규칙의 물리를 고정한다.
 *
 * <p>D-228: 차례 주인은 {@code MOVE} 로 걸음을 그 자리에서 확정할 수 있다 — 같은 {@link Walker} 를 부르고, 쓴 연료는 상태의
 * {@code moveFuelUsed} 에 남아 {@code FIRE.moveDx}·딜레이가 이어서 센다. 차례가 여러 액션이 되므로 차례 시작 시각
 * {@code turnStartedAt} 을 상태에 두고 차례 전체 마감({@link #turnRemainingMillis})을 계산한다(요트 D-183 과 같은 방식).
 * D-229: {@link #preview} 는 상태를 바꾸지 않는 조준 미리보기다.
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
    /** 넘김·시간 초과의 딜레이 (D-226). */
    public static final int SKIP_DELAY = 80;
    /** 쓴 이동 연료 이만큼마다 딜레이 +1 (D-226). */
    public static final int FUEL_PER_DELAY = 2;

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

    /**
     * 판 설정 (D-225·D-226).
     *
     * @param teams     2:2 팀전(팀 = 좌석 홀짝) — 4·6인만
     * @param turnOrder 차례 규칙
     */
    public record Setup(boolean teams, TurnOrder turnOrder) {
        /** D-226 이전 규칙 — 개인전·좌석 순서(옛 테스트·골든). */
        public static final Setup LEGACY = new Setup(false, TurnOrder.SEAT);
        /** 지금 개인전. */
        public static final Setup SOLO = new Setup(false, TurnOrder.DELAY);
        /** 지금 2:2 팀전. */
        public static final Setup TEAMS = new Setup(true, TurnOrder.DELAY);

        public Setup {
            turnOrder = turnOrder == null ? TurnOrder.DELAY : turnOrder;
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
    public Result startMatch(long seed, int seatCount, List<Integer> botSeats, long nowMillis, Setup setup) {
        ArtilleryState s = placed(seed, seatCount, botSeats, Phase.PICKING, nowMillis + PICK_MS, setup);
        return new Result(s, List.of());
    }

    /** 고르기 단계로 시작 — 개인전·좌석 순서(D-226 이전 규칙, 옛 테스트). */
    public Result startMatch(long seed, int seatCount, List<Integer> botSeats, long nowMillis) {
        return startMatch(seed, seatCount, botSeats, nowMillis, Setup.LEGACY);
    }

    /** 고르기 없이 바로 첫 차례 — 종류를 미리 정한 판(테스트·시뮬레이션). 개인전·좌석 순서. */
    public Result startMatch(long seed, int seatCount, List<Integer> botSeats, List<UnitKind> kinds) {
        return startMatch(seed, seatCount, botSeats, kinds, Setup.LEGACY);
    }

    /** 고르기 없이 바로 첫 차례 — 종류를 미리 정한 판(테스트·시뮬레이션). */
    public Result startMatch(long seed, int seatCount, List<Integer> botSeats, List<UnitKind> kinds, Setup setup) {
        if (kinds.size() != seatCount) {
            throw new IllegalArgumentException("kinds size " + kinds.size() + " != seats " + seatCount);
        }
        ArtilleryState s = placed(seed, seatCount, botSeats, Phase.PICKING, 0, setup);
        List<Unit> units = new ArrayList<>(s.units());
        for (int i = 0; i < seatCount; i++) {
            units.set(i, units.get(i).picked(kinds.get(i)));
        }
        List<ArtilleryEvent> events = new ArrayList<>();
        ArtilleryState started = reveal(withUnits(s, units), events, 0L);   // 시계 없이 연 판 — 첫 차례 시계 없음(테스트)
        return new Result(started, events);
    }

    /** 전원 새싹포로 바로 시작(S1~S3 의 규칙 그대로 — 기존 테스트). */
    public Result startMatch(long seed, int seatCount, List<Integer> botSeats) {
        return startMatch(seed, seatCount, botSeats, Setup.LEGACY);
    }

    /** 전원 새싹포로 바로 시작. */
    public Result startMatch(long seed, int seatCount, List<Integer> botSeats, Setup setup) {
        List<UnitKind> kinds = new ArrayList<>();
        for (int i = 0; i < seatCount; i++) {
            kinds.add(UnitKind.SPROUT);
        }
        return startMatch(seed, seatCount, botSeats, kinds, setup);
    }

    private ArtilleryState placed(long seed, int seatCount, List<Integer> botSeats, Phase phase, long pickUntil,
                                  Setup setup) {
        if (seatCount < MIN_PLAYERS || seatCount > MAX_PLAYERS) {
            throw new IllegalArgumentException("seat count out of range: " + seatCount);
        }
        if (setup.teams() && (seatCount < 4 || seatCount % 2 != 0)) {
            throw new IllegalArgumentException("teams need an even seat count >= 4: " + seatCount);
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
                -1, -1, 0, 0, Fixed.HEIGHT, 0, 0, null, null, pickUntil, setup.teams(), setup.turnOrder());
    }

    /**
     * 고르기 끝 (D-224): 안 고른 좌석은 새싹포, 전원 무장 → 공개 이벤트 → 첫 차례(시드로 고른 좌석). 딜레이 규칙(D-226)이면 첫 좌석부터
     * 좌석 순서로 딜레이 0·1·2… 를 깐다 — 첫 바퀴는 좌석 순서와 같다(팀전이면 팀이 번갈아).
     */
    private ArtilleryState reveal(ArtilleryState s, List<ArtilleryEvent> events, long nowMillis) {
        int first = SplitMix64.below(s.seed(), 6000, s.seatCount());
        List<Unit> units = new ArrayList<>(s.units());
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            u = (u.kind() == null ? u.picked(UnitKind.SPROUT) : u).equipped();
            if (s.turnOrder() == TurnOrder.DELAY) {
                u = u.withDelay(Math.floorMod(i - first, s.seatCount()));
            }
            units.set(i, u);
        }
        ArtilleryState armed = new ArtilleryState(s.seed(), s.seatCount(), s.botSeats(), units, s.craters(),
                Phase.AIMING, -1, first, 0, 0, s.cloudLevel(), 0, s.eliminations(), null, null, 0L, s.teams(),
                s.turnOrder());
        events.add(new ArtilleryEvent.UnitsRevealed(
                units.stream().map(u -> ArtilleryPublicView.UnitView.of(s, u, false)).toList()));
        return beginTurn(armed, first, 0, events, nowMillis);
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
            return pick(state, seat, pick, nowMillis);
        }
        if (state.phase() != Phase.AIMING || state.turnSeat() != seat) {
            throw new ArtilleryActionRejectedException(RejectionReason.NOT_YOUR_TURN);
        }
        return switch (action) {
            case ArtilleryAction.Fire fire -> fire(state, seat, fire, nowMillis);
            case ArtilleryAction.Move move -> move(state, seat, move, nowMillis);
            case ArtilleryAction.Skip __ -> skip(state, seat, ArtilleryEvent.TurnSkipped.Reason.SKIP, nowMillis);
            case ArtilleryAction.Pick __ -> throw new ArtilleryActionRejectedException(RejectionReason.INVALID_ACTION);
            // 미리보기는 액션이 아니다 — /preview 경로의 preview() 로만(D-229).
            case ArtilleryAction.Preview __ -> throw new ArtilleryActionRejectedException(RejectionReason.INVALID_ACTION);
            case null -> throw new ArtilleryActionRejectedException(RejectionReason.INVALID_ACTION);
        };
    }

    private Result pick(ArtilleryState state, int seat, ArtilleryAction.Pick pick, long nowMillis) {
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
            s = reveal(s, events, nowMillis);
        }
        return new Result(s, events);
    }

    /** 시간 초과 = 넘김(§4-5). 차례가 아니면 null. */
    public Result timeout(ArtilleryState state, long nowMillis) {
        if (state.phase() != Phase.AIMING) {
            return null;
        }
        return skip(state, state.turnSeat(), ArtilleryEvent.TurnSkipped.Reason.TIMEOUT, nowMillis);
    }

    /**
     * 차례 전체 마감까지 남은 시간 (D-228, D-183 요트와 같은 방식) — {@code turnStartedAt + turnSeconds − 지금}. AIMING 이 아니거나
     * 제한이 없거나({@code turnSeconds ≤ 0}) 차례 시계가 없으면(D-228 이전 판·시계 없이 연 판) 없음. 만기면 {@link #timeout}.
     */
    public OptionalLong turnRemainingMillis(ArtilleryState state, long nowMillis, int turnSeconds) {
        if (state.phase() != Phase.AIMING || turnSeconds <= 0 || state.turnStartedAt() <= 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(state.turnStartedAt() + turnSeconds * 1000L - nowMillis);
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
            case RESOLVING -> new Result(nextTurn(state, events, nowMillis), events);
            case PICKING -> new Result(reveal(state, events, nowMillis), events);
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

    /** 시계 없이 — 다음 차례 시계 없음(테스트·시뮬레이션). */
    public Desertion desert(ArtilleryState state, int seat) {
        return desert(state, seat, 0L);
    }

    public Desertion desert(ArtilleryState state, int seat, long nowMillis) {
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
            s = reveal(s, events, nowMillis);
            return new Desertion(s.ended() ? DesertOutcome.MATCH_ENDED : DesertOutcome.MATCH_CONTINUES, s, events);
        }
        if (s.phase() == Phase.AIMING && s.turnSeat() == seat) {
            s = nextTurn(s, events, nowMillis);
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

        // 아이템 (§6.4) — 쓰는 순간 없어진다. 물뿌리개는 걷기 전에(D-228 — MOVE 로 이미 걸은 뒤, moveDx 걷기 전).
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

        // 이동 — 이번 차례에 MOVE 로 쓴 연료를 뺀 나머지로(D-228)
        UnitKind kind = shooter.kindOrDefault();
        Walker.Walk walk = Walker.walk(t, shooter.x(), shooter.footY(), fire.moveDx(), state.turnFuelLeft(), cloud);
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
            if (!me.alive() || units.stream().filter(Unit::alive).map(u -> state.teamOf(u.seat())).distinct().count() < 2
                    && v > 0) {
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
        if (state.turnOrder() == TurnOrder.DELAY) {
            int fuelUsed = kind.fuel() - walk.fuelLeft();
            int cost = fire.shell().delay() + fuelUsed / FUEL_PER_DELAY + (fire.item() == null ? 0 : fire.item().delay());
            Unit u = units.get(seat);
            units.set(seat, u.withDelay(u.delay() + cost));
        }

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

    /** 넘김·시간 초과 (§4.2): 딜레이 80 + 이번 차례 MOVE 로 쓴 연료의 이동 딜레이(D-228 — 걸어 놓고 넘겨 딜레이를 아끼지 못하게). */
    private Result skip(ArtilleryState state, int seat, ArtilleryEvent.TurnSkipped.Reason reason, long nowMillis) {
        List<ArtilleryEvent> events = new ArrayList<>();
        events.add(new ArtilleryEvent.TurnSkipped(seat, reason));
        List<Unit> units = state.units();
        if (state.turnOrder() == TurnOrder.DELAY) {
            units = new ArrayList<>(units);
            int cost = SKIP_DELAY + state.moveFuelUsed() / FUEL_PER_DELAY;
            units.set(seat, units.get(seat).withDelay(units.get(seat).delay() + cost));
        }
        ArtilleryState s = copy(state, units, state.craters(), state.phase(), state.turnSeat(), seat,
                state.turnNo(), state.wind(), state.cloudLevel(), 0, state.eliminations(), state.lastShot());
        return new Result(nextTurn(s, events, nowMillis), events);
    }

    // ---------- 걷기 확정 (D-228) ----------

    /**
     * {@code MOVE} — {@code FIRE} 안의 이동 단계와 같은 {@link Walker} 로 걷고 그 자리에서 확정한다. 한 걸음도 못 가면 {@code CANNOT_MOVE}
     * (빈 이벤트로 순번을 쓰지 않게). 치명적이지 않은 낙하는 피해만 반영하고 차례가 계속된다. 구름 바다·체력 0 이면 탈락 → 매치 종료 판정 →
     * 아니면 연출 대기({@code RESOLVING}, 걸음 × 2틱 + 떨어진 칸) → 다음 차례({@code FIRE} 이동 중 사망과 같다).
     */
    private Result move(ArtilleryState state, int seat, ArtilleryAction.Move move, long nowMillis) {
        if (move.dx() == 0 || Math.abs(move.dx()) > ArtilleryAction.MAX_MOVE) {
            throw new ArtilleryActionRejectedException(RejectionReason.OUT_OF_RANGE);
        }
        int fuelBefore = state.turnFuelLeft();
        if (fuelBefore <= 0) {
            throw new ArtilleryActionRejectedException(RejectionReason.CANNOT_MOVE);
        }
        Terrain t = terrainOf(state);
        int cloud = state.cloudLevel();
        Unit me = state.unit(seat);
        Walker.Walk walk = Walker.walk(t, me.x(), me.footY(), move.dx(), fuelBefore, cloud);
        if (walk.steps() == 0) {
            throw new ArtilleryActionRejectedException(RejectionReason.CANNOT_MOVE);
        }
        int order = state.eliminations() + 1;
        Unit moved = me.withPosition(walk.x(), walk.footY());
        ShotReport.Fall fall = null;
        Unit.Cause out = null;
        if (walk.fall() != null) {
            Body.Fall f = walk.fall();
            if (f.intoCloud()) {
                out = Unit.Cause.CLOUD_SEA;
            } else {
                moved = moved.withHp(Math.max(0, moved.hp() - f.damage()));
                if (moved.hp() == 0) {
                    out = Unit.Cause.KNOCKED_OUT;
                }
            }
            fall = new ShotReport.Fall(seat, walk.x(), f.fromY(), f.toY(), f.intoCloud() ? 0 : f.damage(), moved.hp(),
                    f.intoCloud());
        }
        if (out != null) {
            moved = moved.eliminated(new Unit.Elimination(order, out, me.hp()));
        }
        List<Unit> units = new ArrayList<>(state.units());
        units.set(seat, moved);
        List<ArtilleryEvent> events = new ArrayList<>();
        events.add(new ArtilleryEvent.UnitMoved(seat, state.turnNo(), walk.path(), walk.x(), walk.footY(),
                walk.fuelLeft(), fall, moved.hp(), out));
        int fuelUsed = state.moveFuelUsed() + (fuelBefore - walk.fuelLeft());
        if (out == null) {
            // 차례 계속 — D-228 이전 판(시계 없음)이면 첫 MOVE 가 차례 시계를 채운다(늘어나는 시간은 많아야 한 번의 T).
            long startedAt = state.turnStartedAt() > 0 ? state.turnStartedAt() : nowMillis;
            ArtilleryState s = copy(state, units, state.craters(), Phase.AIMING, seat, state.lastSeat(), state.turnNo(),
                    state.wind(), cloud, 0, state.eliminations(), state.lastShot()).withTurnClock(startedAt, fuelUsed);
            return new Result(s, events);
        }
        long ticks = (long) walk.steps() * WALK_TICKS_PER_STEP + walk.fall().distance();
        long durationMs = Math.clamp(ticks * 1000 / 60, minResolveMs, maxResolveMs);
        ArtilleryState resolved = copy(state, units, state.craters(), Phase.RESOLVING, -1, seat, state.turnNo(),
                state.wind(), cloud, nowMillis + durationMs, order, null);
        ArtilleryState ended = maybeEnd(resolved, events);
        return new Result(ended != null ? ended : resolved, events);
    }

    // ---------- 조준 미리보기 (D-229) ----------

    /**
     * 상태를 바꾸지 않는 조준 미리보기 — 순수·가볍다(비교 몇 개). AIMING 의 차례 주인이 각도 0~180 을 보낼 때만 {@code AIM_PREVIEW}, 아니면
     * 없음(거절 ERROR 를 보내지 않는다). 탄은 그 포대가 지금 쏠 수 있을 때만 싣는다. 힘·위치는 싣지 않는다.
     */
    public Optional<ArtilleryEvent.AimPreview> preview(ArtilleryState state, int seat,
                                                                 ArtilleryAction.Preview input) {
        if (input == null || state.phase() != Phase.AIMING || state.turnSeat() != seat
                || input.angle() < 0 || input.angle() > 180) {
            return Optional.empty();
        }
        Shell shell = input.shell() != null && state.unit(seat).ammo(input.shell()) != 0 ? input.shell() : null;
        return Optional.of(new ArtilleryEvent.AimPreview(seat, state.turnNo(), input.angle(), shell, input.n()));
    }

    // ---------- 차례 ----------

    /** 다음 차례 주인({@link #pickNext})으로 차례 번호 +1. */
    private ArtilleryState nextTurn(ArtilleryState state, List<ArtilleryEvent> events, long nowMillis) {
        int next = pickNext(state.turnOrder(), state.units(), state.lastSeat());
        if (next < 0) {
            ArtilleryState ended = maybeEnd(state, events);
            if (ended != null) {
                return ended;
            }
            throw new IllegalStateException("no alive seat but match not over");
        }
        return beginTurn(state, next, state.turnNo() + 1, events, nowMillis);
    }

    /**
     * 차례 시작 (§4-1): 밀물 → (끝났나) → 바람 → AIMING. {@code candidate} 가 밀물에 빠지면 그 다음 살아 있는 좌석. 차례 시계는
     * {@code nowMillis}(D-228, 0 이면 시계 없음)에서, MOVE 연료는 0 에서 시작한다.
     */
    private ArtilleryState beginTurn(ArtilleryState state, int candidate, int turnNo, List<ArtilleryEvent> events,
                                     long nowMillis) {
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
        int seat = units.get(candidate).alive() ? candidate
                : state.turnOrder() == TurnOrder.DELAY ? pickNext(TurnOrder.DELAY, units, state.lastSeat())
                : nextAlive(units, candidate, false);
        s = copy(s, units, s.craters(), Phase.AIMING, seat, seat, turnNo, wind, cloud, 0, eliminations, null)
                .withTurnClock(nowMillis, 0);
        events.add(new ArtilleryEvent.TurnChanged(seat, turnNo, wind, cloud,
                units.stream().map(Unit::delay).toList(), turnQueue(s)));
        return s;
    }

    /**
     * 다음 차례 주인. 좌석 순서면 {@code lastSeat} 다음 살아 있는 좌석, 딜레이(D-226)면 딜레이가 가장 작은 살아 있는 좌석 — 같으면
     * {@code lastSeat} 다음부터 좌석 순서(방금 쏜 좌석이 맨 뒤). 살아 있는 좌석이 없으면 −1.
     */
    private static int pickNext(TurnOrder order, List<Unit> units, int lastSeat) {
        if (order != TurnOrder.DELAY) {
            return nextAlive(units, lastSeat, false);
        }
        int n = units.size();
        int best = -1;
        for (int i = 1; i <= n; i++) {
            int s = Math.floorMod(lastSeat + i, n);
            Unit u = units.get(s);
            if (u.alive() && (best < 0 || u.delay() < units.get(best).delay())) {
                best = s;
            }
        }
        return best;
    }

    /**
     * 앞으로의 차례 순서 (D-226) — 살아 있는 좌석을 지금 차례 주인부터(딜레이 규칙이면 딜레이 → 좌석 순서, 좌석 규칙이면 좌석 순서로).
     * 화면의 "다음 차례" 줄이다. 차례 주인이 없으면(연출 대기·끝) 다음에 고를 좌석부터.
     */
    public static List<Integer> turnQueue(ArtilleryState state) {
        List<Unit> units = state.units();
        int n = units.size();
        int anchor = state.turnSeat() >= 0 ? Math.floorMod(state.turnSeat() - 1, n) : state.lastSeat();
        if (anchor < 0) {
            anchor = n - 1;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            int s = Math.floorMod(anchor + i, n);
            if (units.get(s).alive()) {
                order.add(s);
            }
        }
        if (state.turnOrder() == TurnOrder.DELAY) {
            if (state.turnSeat() >= 0) {
                int head = state.turnSeat();
                List<Integer> rest = new ArrayList<>(order);
                rest.remove(Integer.valueOf(head));
                rest.sort(Comparator.comparingInt(s -> units.get(s).delay()));   // 안정 정렬 — 좌석 순서가 동률 가르기
                order = new ArrayList<>();
                order.add(head);
                order.addAll(rest);
            } else {
                order.sort(Comparator.comparingInt(s -> units.get(s).delay()));
            }
        }
        return order;
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
        if (state.teams()) {
            return maybeEndTeams(state, events);
        }
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
        return ended(state, new MatchResult(reason, placements(state.units())), events);
    }

    private static ArtilleryState ended(ArtilleryState state, MatchResult result, List<ArtilleryEvent> events) {
        events.add(new ArtilleryEvent.MatchEnded(result.reason(), result.placements(), result.teams(),
                result.winnerTeam()));
        return new ArtilleryState(state.seed(), state.seatCount(), state.botSeats(), state.units(), state.craters(),
                Phase.ENDED, -1, state.lastSeat(), state.turnNo(), state.wind(), state.cloudLevel(), 0,
                state.eliminations(), state.lastShot(), result, state.pickUntil(), state.teams(), state.turnOrder());
    }

    /**
     * 팀전 종료 (D-225): 살아 있는 팀이 하나면 그 팀 승. 둘 다 전멸하면 마지막 탈락 순간의 <b>탈락 직전 체력 합</b>(먼저 탈락한 팀원은 그
     * 순간 체력이 없으니 0)이 큰 팀 승, 같으면 무승부. 사람이 있던 판에서 사람이 모두 탈락하면 살아 있는 수 → 남은 체력 합 → 무승부.
     */
    private ArtilleryState maybeEndTeams(ArtilleryState state, List<ArtilleryEvent> events) {
        MatchResult result = teamOutcome(state);
        return result == null ? null : ended(state, result, events);
    }

    /** 팀전 결과 — 아직 안 끝났으면 null. */
    static MatchResult teamOutcome(ArtilleryState state) {
        List<Unit> alive = state.aliveUnits();
        long teamsAlive = alive.stream().map(u -> state.teamOf(u.seat())).distinct().count();
        MatchResult.EndReason reason;
        Integer winner;
        if (teamsAlive == 1) {
            reason = MatchResult.EndReason.LAST_STANDING;
            winner = state.teamOf(alive.getFirst().seat());
        } else if (teamsAlive == 0) {
            reason = MatchResult.EndReason.ALL_DOWN;
            int[] sum = new int[2];
            for (Unit u : state.units()) {
                if (u.out().order() == state.eliminations()) {
                    sum[state.teamOf(u.seat())] += u.out().hpBefore();
                }
            }
            winner = sum[0] == sum[1] ? null : sum[0] > sum[1] ? 0 : 1;
        } else if (state.botSeats().size() < state.seatCount()
                && alive.stream().allMatch(u -> state.botSeats().contains(u.seat()))) {
            reason = MatchResult.EndReason.NO_HUMANS;
            int[] count = new int[2];
            int[] hp = new int[2];
            for (Unit u : alive) {
                count[state.teamOf(u.seat())]++;
                hp[state.teamOf(u.seat())] += u.hp();
            }
            winner = count[0] != count[1] ? (count[0] > count[1] ? 0 : 1)
                    : hp[0] != hp[1] ? (hp[0] > hp[1] ? 0 : 1) : null;
        } else {
            return null;
        }
        return new MatchResult(reason, teamPlacements(state.units(), winner), true, winner);
    }

    /** 팀 순위 (D-225): 이긴 팀(무승부면 모두) → 진 팀 → 탈주자. 같은 묶음은 동순위. */
    static List<MatchResult.Placement> teamPlacements(List<Unit> units, Integer winner) {
        int[] group = new int[units.size()];
        for (Unit u : units) {
            boolean deserted = !u.alive() && u.out().cause() == Unit.Cause.DESERTED;
            group[u.seat()] = deserted ? 2 : winner == null || u.seat() % 2 == winner ? 0 : 1;
        }
        List<MatchResult.Placement> placements = new ArrayList<>();
        for (Unit u : units) {
            int mine = group[u.seat()];
            int rank = 1 + (int) Arrays.stream(group).filter(g -> g < mine).count();
            placements.add(new MatchResult.Placement(u.seat(), rank, u.hp(), mine == 2));
        }
        placements.sort(Comparator.comparingInt(MatchResult.Placement::rank).thenComparingInt(MatchResult.Placement::seat));
        return placements;
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
                s.result(), s.pickUntil(), s.teams(), s.turnOrder(), s.turnStartedAt(), s.moveFuelUsed());
    }

    /** 차례 시계·MOVE 연료는 AIMING 이면 그대로 잇고, 그 밖의 단계면 0 이다(D-228). */
    private static ArtilleryState copy(ArtilleryState s, List<Unit> units, List<Crater> craters, Phase phase,
                                       int turnSeat, int lastSeat, int turnNo, int wind, int cloud, long resolveUntil,
                                       int eliminations, ShotReport lastShot) {
        boolean aiming = phase == Phase.AIMING;
        return new ArtilleryState(s.seed(), s.seatCount(), s.botSeats(), units, craters, phase, turnSeat, lastSeat,
                turnNo, wind, cloud, resolveUntil, eliminations, lastShot, s.result(), s.pickUntil(), s.teams(),
                s.turnOrder(), aiming ? s.turnStartedAt() : 0L, aiming ? s.moveFuelUsed() : 0);
    }
}
