package com.mirboard.domain.game.artillery.invariant;

import com.mirboard.domain.game.artillery.ArtilleryEngine;
import com.mirboard.domain.game.artillery.physics.Body;
import com.mirboard.domain.game.artillery.physics.Terrain;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.MatchResult;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.artillery.state.Unit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 매 전이 뒤 지켜져야 할 것 (`docs/rules-artillery.md` §12). 위반 목록을 돌려준다(비면 정상) — 시뮬레이션·테스트가 쓴다.
 */
public final class ArtilleryInvariantChecker {

    private ArtilleryInvariantChecker() {
    }

    public static List<String> check(ArtilleryState s) {
        List<String> v = new ArrayList<>();
        if (s.units().size() != s.seatCount()) {
            v.add("units size " + s.units().size() + " != seatCount " + s.seatCount());
        }
        Terrain t = ArtilleryEngine.terrainOf(s);
        for (int i = 0; i < s.units().size(); i++) {
            Unit u = s.units().get(i);
            if (u.seat() != i) {
                v.add("unit index " + i + " has seat " + u.seat());
            }
            if (u.hp() < 0 || u.hp() > ArtilleryEngine.MAX_HP) {
                v.add("seat " + i + " hp out of range: " + u.hp());
            }
            if (u.pumpkins() < 0 || u.pumpkins() > Shell.PUMPKIN.perMatch()) {
                v.add("seat " + i + " pumpkins out of range: " + u.pumpkins());
            }
            if (u.alive()) {
                if (u.hp() == 0) {
                    v.add("seat " + i + " alive with hp 0");
                }
                if (u.footY() >= s.cloudLevel()) {
                    v.add("seat " + i + " alive below cloud: footY=" + u.footY() + " cloud=" + s.cloudLevel());
                }
                if (!Body.supported(t, u.x(), u.footY())) {
                    v.add("seat " + i + " alive without support at (" + u.x() + "," + u.footY() + ")");
                }
            } else if (u.out().order() < 1 || u.out().order() > s.eliminations()) {
                v.add("seat " + i + " elimination order " + u.out().order() + " outside 1.." + s.eliminations());
            }
        }
        switch (s.phase()) {
            case AIMING -> {
                if (s.turnSeat() < 0 || s.turnSeat() >= s.seatCount() || !s.unit(s.turnSeat()).alive()) {
                    v.add("AIMING turn seat not alive: " + s.turnSeat());
                }
            }
            case RESOLVING -> {
                if (s.turnSeat() != -1) {
                    v.add("RESOLVING with turn seat " + s.turnSeat());
                }
                if (s.resolveUntil() <= 0) {
                    v.add("RESOLVING without resolveUntil");
                }
            }
            case ENDED -> {
                MatchResult r = s.result();
                if (r == null) {
                    v.add("ENDED without result");
                } else {
                    Set<Integer> seats = new HashSet<>();
                    r.placements().forEach(p -> seats.add(p.seat()));
                    if (seats.size() != s.seatCount()) {
                        v.add("placements cover " + seats.size() + " of " + s.seatCount() + " seats");
                    }
                }
            }
        }
        if (s.phase() != Phase.ENDED && s.aliveUnits().size() <= 1) {
            v.add("not ended with " + s.aliveUnits().size() + " alive");
        }
        if (s.turnNo() >= ArtilleryEngine.turnLimit(s.seatCount())) {
            v.add("turn " + s.turnNo() + " reached limit " + ArtilleryEngine.turnLimit(s.seatCount()));
        }
        if (s.wind() < -ArtilleryEngine.WIND_MAX || s.wind() > ArtilleryEngine.WIND_MAX) {
            v.add("wind out of range: " + s.wind());
        }
        return v;
    }
}
