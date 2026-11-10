package com.mirboard.domain.game.artillery;

import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.rules.Item;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.rules.UnitKind;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.artillery.state.ShotReport;
import com.mirboard.domain.game.artillery.state.Unit;
import java.util.List;

/** 물리 지문 — JSON 모양과 무관하게 두 판(S3·S4 골든)의 지형 해시·포대·대본 충돌점·피해를 한 줄로. */
final class ArtilleryPhysicsPrint {

    private static final ArtilleryEngine ENGINE = new ArtilleryEngine();

    static String print() {
        StringBuilder sb = new StringBuilder();
        long now = 1_000_000L;
        ArtilleryState s = ENGINE.startMatch(20261109L, 4, List.of()).newState();
        int[][] shots = {{0, 60, 55}, {10, 120, 60}, {-20, 45, 70}, {0, 135, 50}, {30, 70, 40}, {0, 100, 65},
                {-10, 80, 75}, {0, 30, 45}, {5, 150, 55}, {0, 90, 35}, {-30, 65, 80}, {0, 110, 50}};
        for (int i = 0; i < shots.length && !s.ended(); i++) {
            Shell shell = i % 4 == 1 && s.unit(s.turnSeat()).pumpkins() > 0 ? Shell.PUMPKIN : Shell.PEA;
            sb.append('T').append(s.turnSeat());
            ArtilleryEngine.Result r = ENGINE.apply(s, s.turnSeat(),
                    new ArtilleryAction.Fire(shots[i][0], shots[i][1], shots[i][2], shell), now);
            events(sb, r.events());
            s = r.newState();
            if (s.phase() == Phase.RESOLVING) {
                now = s.resolveUntil();
                r = ENGINE.expire(s, now);
                s = r.newState();
            }
        }
        state(sb, s);
        sb.append("\n");
        now = 1_000_000L;
        s = ENGINE.startMatch(20261110L, 3, List.of(), now).newState();
        UnitKind[] kinds = {UnitKind.ACORN, UnitKind.DANDELION, UnitKind.SPROUT};
        for (int seat = 0; seat < 3; seat++) {
            s = ENGINE.apply(s, seat, new ArtilleryAction.Pick(kinds[seat]), now).newState();
        }
        int[][] shots2 = {{0, 60, 55}, {10, 120, 60}, {-20, 45, 70}, {0, 135, 50}, {30, 70, 40}, {0, 100, 65},
                {-10, 80, 75}, {0, 30, 45}, {5, 150, 55}};
        Item[] items = {null, Item.TWIN_SEED, null, Item.PINWHEEL, null, Item.WATERING_CAN, null, null, null};
        for (int i = 0; i < shots2.length && !s.ended(); i++) {
            Unit me = s.unit(s.turnSeat());
            Shell shell = i % 3 == 0 && me.specials() > 0 ? me.kind().special() : Shell.PEA;
            Item item = items[i] != null && me.has(items[i]) ? items[i] : null;
            sb.append('T').append(s.turnSeat());
            ArtilleryEngine.Result r = ENGINE.apply(s, s.turnSeat(),
                    new ArtilleryAction.Fire(shots2[i][0], shots2[i][1], shots2[i][2], shell, item), now);
            events(sb, r.events());
            s = r.newState();
            if (s.phase() == Phase.RESOLVING) {
                now = s.resolveUntil();
                s = ENGINE.expire(s, now).newState();
            }
        }
        state(sb, s);
        return sb.toString();
    }

    private static void events(StringBuilder sb, List<ArtilleryEvent> events) {
        for (ArtilleryEvent e : events) {
            if (e instanceof ArtilleryEvent.ShotResolved sr) {
                ShotReport rep = sr.report();
                sb.append("[m").append(rep.move().toX()).append(',').append(rep.move().toY());
                for (ShotReport.Stage st : rep.stages()) {
                    for (ShotReport.Shot sh : st.shots()) {
                        sb.append(" i").append(sh.impactX()).append(',').append(sh.impactY()).append(sh.end())
                                .append(sh.path().size());
                    }
                    for (ShotReport.Damage d : st.damages()) {
                        sb.append(" d").append(d.seat()).append(':').append(d.amount());
                    }
                    for (ShotReport.Fall f : st.falls()) {
                        sb.append(" f").append(f.seat()).append(':').append(f.toY());
                    }
                }
                sb.append(" ms").append(rep.durationMs()).append(']');
            }
        }
    }

    private static void state(StringBuilder sb, ArtilleryState s) {
        sb.append(" terrain=").append(Long.toHexString(ArtilleryEngine.terrainOf(s).hash()));
        for (Unit u : s.units()) {
            sb.append(" u").append(u.seat()).append('@').append(u.x()).append(',').append(u.footY()).append(" hp")
                    .append(u.hp()).append(u.alive() ? "" : "x" + u.out().cause());
        }
        sb.append(" turn").append(s.turnNo()).append(" cloud").append(s.cloudLevel());
    }
}
