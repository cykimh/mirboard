package com.mirboard.domain.game.artillery;

import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.physics.Ballistics;
import com.mirboard.domain.game.artillery.physics.Body;
import com.mirboard.domain.game.artillery.physics.Fixed;
import com.mirboard.domain.game.artillery.physics.Terrain;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Unit;
import java.util.ArrayList;
import java.util.List;

/** 엔진 테스트 도우미. */
public final class ArtilleryFixtures {

    public static final ArtilleryEngine ENGINE = new ArtilleryEngine();

    private ArtilleryFixtures() {
    }

    public static ArtilleryState start(long seed, int seats, Integer... bots) {
        return ENGINE.startMatch(seed, seats, List.of(bots)).newState();
    }

    public static ArtilleryState withUnit(ArtilleryState s, Unit u) {
        List<Unit> units = new ArrayList<>(s.units());
        units.set(u.seat(), u);
        return new ArtilleryState(s.seed(), s.seatCount(), s.botSeats(), units, s.craters(), s.phase(), s.turnSeat(),
                s.lastSeat(), s.turnNo(), s.wind(), s.cloudLevel(), s.resolveUntil(), s.eliminations(), s.lastShot(),
                s.result(), s.pickUntil());
    }

    /** 차례 주인이 {@code target} 을 직격하는 (각도, 힘)을 찾는다 — 없으면 null. */
    public static ArtilleryAction.Fire directHit(ArtilleryState s, int target, Shell shell) {
        Terrain t = ArtilleryEngine.terrainOf(s);
        int seat = s.turnSeat();
        Unit me = s.unit(seat);
        List<Ballistics.Target> targets = s.aliveUnits().stream()
                .map(u -> new Ballistics.Target(u.seat(), u.x(), u.footY())).toList();
        for (int angle = 0; angle <= 180; angle++) {
            for (int power = 1; power <= 100; power++) {
                Ballistics.Trace tr = Ballistics.fire(t, targets, seat, me.x(), me.footY(), angle, power, s.wind(),
                        s.cloudLevel());
                if (tr.end() == Ballistics.End.UNIT && tr.hitSeat() == target) {
                    return new ArtilleryAction.Fire(0, angle, power, shell);
                }
            }
        }
        return null;
    }

    /** 몸 중심 칸이 화면 위쪽인지 — 테스트 가독성용. */
    static int bodyY(Unit u) {
        return Body.centerY(u.footY());
    }

    static int width() {
        return Fixed.WIDTH;
    }
}
